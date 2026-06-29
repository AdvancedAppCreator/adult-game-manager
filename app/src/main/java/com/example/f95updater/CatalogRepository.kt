package com.example.f95updater

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
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

private fun catalogChoicesForLog(games: List<CatalogGame>): String =
    games.take(12).joinToString(prefix = "[", postfix = "]") {
        "${it.thread_id}:'${it.title}' norm='${CatalogRepository.normalizeTitle(it.title)}'"
    } + if (games.size > 12) " +${games.size - 12} more" else ""

sealed class CatalogSyncResult {
    data class Updated(val gameCount: Int, val sizeBytes: Long) : CatalogSyncResult()
    object NotModified : CatalogSyncResult()
    data class Error(val message: String) : CatalogSyncResult()
}

/** Manages the cached source catalogs (and the labels file). Uses ETag/Last-Modified
 *  so subsequent syncs return 304 with no payload when unchanged. */
class CatalogRepository(private val context: Context) {

    companion object {
        /** Normalize a title or app label for matching: lowercase, alphanumeric only. */
        fun normalizeTitle(s: String): String =
            s.lowercase().replace(Regex("[^a-z0-9]"), "")

        /** Tokenize: lowercase, split on non-alphanumeric, keep tokens of length >= 3. */
        fun tokenize(s: String): List<String> =
            s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }

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

        private fun numberEquivalentTokens(tokens: List<String>): List<String> {
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

        private fun numberEquivalentKey(s: String): String =
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

        private fun rawTitleTokens(s: String): List<String> {
            var x = s
                .replace(Regex("\\[[^\\]]*\\]"), " ")
                .replace(Regex("\\([^)]*\\)"), " ")
            x = x.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            x = x.replace(Regex("([A-Z])([A-Z][a-z])"), "$1 $2")
            return x.lowercase()
                .split(Regex("[^a-z0-9]+"))
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
                val isProductCode = t.matches(Regex("^rj\\d+$"))
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

        private fun similarity(a: String, b: String): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]
                dp[0] = i
                for (j in 1..b.length) {
                    val old = dp[j]
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                    prev = old
                }
            }
            val distance = dp[b.length]
            return 1.0 - (distance.toDouble() / maxOf(a.length, b.length).toDouble())
        }

        fun safeCleanTitleMatch(appKey: String, catalogKey: String): Double {
            if (appKey.length < 4 || catalogKey.length < 4) return 0.0
            if (appKey == catalogKey) return 1.0
            val shorter = minOf(appKey.length, catalogKey.length).toDouble()
            val longer = maxOf(appKey.length, catalogKey.length).toDouble()
            val lengthRatio = shorter / longer
            if (catalogKey.startsWith(appKey)) {
                val extra = catalogKey.removePrefix(appKey)
                if (lengthRatio >= 0.75 || extra.matches(Regex("^(?:vol|part|book|chapter|ch|day)?\\d+$"))) return 0.92
            }
            if (appKey.startsWith(catalogKey) && lengthRatio >= 0.75) return 0.90
            if (appKey.startsWith("s") && appKey.drop(1) == catalogKey) return 0.96
            return similarity(appKey, catalogKey).takeIf { it >= 0.94 } ?: 0.0
        }

        private val articlesToDrop = setOf("the", "a", "an")
        private val verifiedAliasThreadIds = mapOf(
            "magicalgirlbreasty" to 205752,
            "magicalgirlmagicalbreasty" to 205752,
        )
        private val relaxedSuggestionThreadIds = mapOf(
            "dmd" to 597,
            "dmdch1" to 597,
            "dmdch2" to 597,
            "dmdch3" to 597,
            "dmdch4" to 597,
        )

        /** Check if [needle] is a contiguous sublist of [haystack]. */
        fun containsContiguous(haystack: List<String>, needle: List<String>): Boolean {
            if (needle.isEmpty() || needle.size > haystack.size) return false
            outer@ for (i in 0..(haystack.size - needle.size)) {
                for (j in needle.indices) {
                    if (haystack[i + j] != needle[j]) continue@outer
                }
                return true
            }
            return false
        }

        /**
         * Strict fuzzy title match using ordered word lists.
         *
         * Match if either:
         *   - The two cleaned word lists are equal, OR
         *   - The shorter list has at least two words and is a PREFIX of the longer list,
         *     and the extra words are harmless release/edition suffixes.
         *
         * The prefix-only rule reflects how adult game folders/APKs are typically named:
         *   "{canonical title}-{version}-{platform}"
         * The catalog's canonical title is always at the start of the app label.
         *
         * Single-token prefix matches are too broad for automatic mapping:
         * "Calendar" should not match "Calendar Bitchy Girlfriend's NTR Fiction",
         * and "The Motel" should not match the Motel 6 app.
         *
         * The original user complaint — "ADM" matching "The Headmaster" via substring
         * within "headmaster" — is rejected here because:
         *   - "adm" is a single token of length 3 -> reject
         *   - And ["adm"] is not a prefix of ["headmaster"] anyway
         */
        fun fuzzyTitleMatch(a: String, b: String): Boolean {
            val wa = titleWords(a)
            val wb = titleWords(b)
            if (wa.isEmpty() || wb.isEmpty()) return false
            if (wa == wb) return true
            val short = if (wa.size <= wb.size) wa else wb
            val long  = if (wa.size <= wb.size) wb else wa
            return prefixTitleWordsMatch(short, long)
        }

        private fun acronym(words: List<String>): String =
            words.mapNotNull { it.firstOrNull() }.joinToString("")

        private fun safeExtraTitleWords(words: List<String>): Boolean =
            words.all {
                it in SAFE_PREFIX_SUFFIX_TOKENS ||
                    it.startsWith("episode") ||
                    it.startsWith("chapter") ||
                    it.startsWith("season") ||
                    VERSION_TOKEN.matches(it)
            }

        fun prefixTitleWordsMatch(short: List<String>, long: List<String>): Boolean {
            if (short.size < 2 || short.size >= long.size) return false
            for (i in short.indices) {
                if (short[i] != long[i]) return false
            }
            return safeExtraTitleWords(long.drop(short.size))
        }

        fun likelySameTitle(appLabel: String, catalogTitle: String, allowAcronym: Boolean = true): Boolean {
            val appNorm = normalizeTitle(appLabel)
            val catalogNorm = normalizeTitle(catalogTitle)
            if (appNorm.isBlank() || catalogNorm.isBlank()) return false
            if (appNorm == catalogNorm) return true
            if (fuzzyTitleMatch(appLabel, catalogTitle)) return true
            if (safeCleanTitleMatch(appCleanKey(appLabel), catalogCleanKey(catalogTitle)) >= 0.90) return true
            val catalogAcronym = acronym(titleWords(catalogTitle))
            return allowAcronym && appNorm.length >= 3 &&
                (appNorm == catalogAcronym || (appNorm.length >= 4 && catalogAcronym.startsWith(appNorm)))
        }
    }

    private val catalogUrl: String get() = AppConfigStore.current(context).catalogUrl
    private val catalogIndexUrl: String get() = AppConfigStore.current(context).catalogIndexUrl
    private val labelsUrl: String get() = AppConfigStore.current(context).labelsUrl

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val catalogFile: File get() = File(context.filesDir, "catalog.json")
    private val labelsFile:  File get() = File(context.filesDir, "labels.json")
    private val sourceIndexFile: File get() = File(context.filesDir, "source_catalog_index.json")
    private val sourceCatalogDir: File get() = File(context.filesDir, "source_catalogs")

    suspend fun lastSyncMs(): Long = context.catalogMetaStore.data.map { it[LASTSYNC_KEY] ?: 0L }.first()
    suspend fun cachedCount(): Long = context.catalogMetaStore.data.map { it[COUNT_KEY] ?: 0L }.first()
    suspend fun cachedSize():  Long = context.catalogMetaStore.data.map { it[SIZE_KEY] ?: 0L }.first()

    /** Downloads (or re-validates) the catalog. Honors If-None-Match / If-Modified-Since.
     *  If the server returns 304 but the local file is missing or empty (e.g. because
     *  we changed where we store it between app versions), force a fresh download. */
    suspend fun sync(): CatalogSyncResult = withContext(Dispatchers.IO) {
        val prefs = context.catalogMetaStore.data.first()
        val haveLocal = catalogFile.exists() && catalogFile.length() > 0L
        val builder = Request.Builder().url(catalogUrl)
        if (haveLocal) {
            prefs[ETAG_KEY]?.let { builder.header("If-None-Match", it) }
            prefs[LASTMOD_KEY]?.let { builder.header("If-Modified-Since", it) }
        }

        runCatching {
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
    }

    private fun mergeSourceSync(legacyResult: CatalogSyncResult): CatalogSyncResult {
        syncLabels()
        val sourceResult = syncSourceCatalogsBlocking()
        if (sourceResult is CatalogSyncResult.Error) {
            AppLog.e("Catalog", "Source catalog sync failed: ${sourceResult.message}")
            return legacyResult
        }
        if (sourceResult is CatalogSyncResult.Updated) {
            val legacyCount = (legacyResult as? CatalogSyncResult.Updated)?.gameCount ?: 0
            val legacySize = (legacyResult as? CatalogSyncResult.Updated)?.sizeBytes ?: 0L
            return CatalogSyncResult.Updated(legacyCount + sourceResult.gameCount, legacySize + sourceResult.sizeBytes)
        }
        return legacyResult
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
            sourceIndexFile.writeBytes(indexBytes)
            val registry = indexBytes.inputStream().use { json.decodeFromStream<CatalogSourceRegistry>(it) }
            SourceRegistry.update(registry.catalogs)
            sourceCatalogDir.mkdirs()
            var totalBytes = indexBytes.size.toLong()
            var totalCount = 0
            // Files we intend to keep this sync. Anything else in the dir (a source that
            // became disabled, raised its minAppVersion above ours, or dropped out of the
            // registry entirely) is stale and must be deleted, or allSourceEntries() would
            // keep rendering it from a previously-synced file.
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
                File(sourceCatalogDir, fileName).writeBytes(bytes)
                keepFiles += fileName
                totalBytes += bytes.size
                totalCount += catalog.count ?: countSourceEntries(bytes)
            }
            sourceCatalogDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.filterNot { it.name in keepFiles }
                ?.forEach { it.delete() }
            invalidateIndex()
            CatalogSyncResult.Updated(totalCount, totalBytes)
        }.getOrElse {
            CatalogSyncResult.Error(it.message ?: "unknown source catalog error")
        }
    }

    private fun syncLabels() {
        val haveLocal = labelsFile.exists() && labelsFile.length() > 0L
        val prefs = runBlocking { context.catalogMetaStore.data.first() }
        val builder = Request.Builder().url(labelsUrl)
        if (haveLocal) {
            prefs[LABELS_ETAG_KEY]?.let { builder.header("If-None-Match", it) }
            prefs[LABELS_LASTMOD_KEY]?.let { builder.header("If-Modified-Since", it) }
        }
        runCatching {
            client.newCall(builder.build()).execute().use { resp ->
                if (resp.code == 200) {
                    resp.body?.bytes()?.let { labelsFile.writeBytes(it) }
                    val etag = resp.header("ETag")
                    val lm = resp.header("Last-Modified")
                    runBlocking {
                        context.catalogMetaStore.edit {
                            if (!etag.isNullOrBlank()) it[LABELS_ETAG_KEY] = etag
                            if (!lm.isNullOrBlank()) it[LABELS_LASTMOD_KEY] = lm
                        }
                    }
                    cachedLabels = null
                }
                // 304: keep existing labels.json. Other codes: leave cache untouched.
            }
        }.onFailure { AppLog.e("Catalog", "label sync failed", it) }
    }

    @Volatile private var cachedIndex:  Map<String, List<CatalogGame>>? = null
    @Volatile private var cachedById:   Map<Int, CatalogGame>? = null
    @Volatile private var cachedAll:    List<CatalogGame>? = null
    @Volatile private var cachedLabels: CatalogLabelsV2? = null
    @Volatile private var cachedSourceEntries: List<SourceCatalogEntry>? = null
    // Pre-computed (titleWords, game) for the whole catalog, and a first-word bucket
    // index. fuzzyTitleMatch is prefix-only, so candidates must share words[0] with the
    // query label. This turns a 208 x 25k brute-force into ~208 x (bucket size, often <20).
    @Volatile private var cachedWordIndex: Map<String, List<Pair<List<String>, CatalogGame>>>? = null
    @Volatile private var cachedCleanIndex: Map<String, List<CatalogGame>>? = null
    @Volatile private var cachedCleanEntries: List<Pair<String, CatalogGame>>? = null
    @Volatile private var cachedAcronymIndex: Map<String, List<CatalogGame>>? = null
    @Volatile private var cachedAcronymPrefixIndex: Map<String, List<CatalogGame>>? = null
    // Pre-computed search rows for free-text search (case-folded lowercase title +
    // normalized title + token list). Built once and reused for every keystroke,
    // eliminating per-keystroke regex work over 25k titles.
    private data class SearchEntry(
        val game: CatalogGame,
        val lowerTitle: String,
        val normTitle: String,
        val numberNormTitle: String,
        val tokens: List<String>,
        val numberTokens: List<String>,
    )
    @Volatile private var cachedSearchEntries: List<SearchEntry>? = null

    private fun invalidateIndex() {
        cachedIndex = null; cachedById = null; cachedAll = null; cachedSourceEntries = null
        cachedWordIndex = null; cachedCleanIndex = null; cachedCleanEntries = null
        cachedAcronymIndex = null; cachedAcronymPrefixIndex = null
        cachedSearchEntries = null
    }

    suspend fun gamesByNormalizedTitle(): Map<String, List<CatalogGame>> = withContext(Dispatchers.IO) {
        cachedIndex ?: run {
            runCatching { allCatalogGames().groupBy { normalizeTitle(it.title) } }
                .onFailure { AppLog.e("Catalog", "parseAndIndex failed (returning empty)", it) }
                .getOrDefault(emptyMap())
                .also { cachedIndex = it }
        }
    }

    suspend fun gamesById(): Map<Int, CatalogGame> = withContext(Dispatchers.IO) {
        cachedById ?: run {
            gamesByNormalizedTitle().values.flatten().associateBy { it.thread_id }.also { cachedById = it }
        }
    }

    /** All games (used for fuzzy token matching). */
    suspend fun allGames(): List<CatalogGame> = withContext(Dispatchers.IO) {
        cachedAll ?: run {
            gamesByNormalizedTitle().values.flatten().toList().also { cachedAll = it }
        }
    }

    private suspend fun allCatalogGames(): List<CatalogGame> {
        val legacyGames = parseLegacyGames()
        val sourceGames = allSourceEntries().map { it.toCatalogGame() }
        if (sourceGames.isEmpty()) return legacyGames
        val sourceF95ThreadIds = sourceGames
            .filter { it.source == SOURCE_F95ZONE && it.thread_id > 0 }
            .map { it.thread_id }
            .toSet()
        return sourceGames + legacyGames.filterNot { it.thread_id in sourceF95ThreadIds }
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

    suspend fun allSourceEntries(): List<SourceCatalogEntry> = withContext(Dispatchers.IO) {
        cachedSourceEntries ?: run {
            val dir = sourceCatalogDir
            val entries = if (dir.exists()) {
                dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
                    ?.flatMap { file -> parseSourceEntries(file) }
                    .orEmpty()
            } else {
                emptyList()
            }
            entries.sortedWith(
                compareBy<SourceCatalogEntry> { it.title.lowercase() }
                    .thenBy { it.source.sourceDisplayName }
                    .thenBy { it.sourceId }
            ).also { cachedSourceEntries = it }
        }
    }

    private fun preferCatalogGame(games: List<CatalogGame>): CatalogGame? = games
        .sortedWith(compareBy<CatalogGame>(
            { if (it.category == "games") 0 else 1 },
            { -it.views },
            { -it.ts },
        ))
        .firstOrNull()

    /** Strict fuzzy match using ordered word lists:
     *    1. Exact normalized-title match (lowercase + alphanum-only equality)
     *    2. Word-list match via [fuzzyTitleMatch] (contiguous sublist of cleaned tokens,
     *       single-token short-side requires length >= 5 to prevent false positives like
     *       "ADM" matching "The Headmaster" via substring within "headmaster")
     *  Ambiguous collisions are rejected: correctness beats coverage, so the UI shows
     *  Unknown instead of persisting a plausible but unrelated thread. */
    suspend fun bestTitleMatch(
        appLabels: List<String>,
        allowAcronym: Boolean = true,
    ): CatalogTitleMatch? = withContext(Dispatchers.IO) {
        val labels = appLabels.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (labels.isEmpty()) return@withContext null
        // 1. Exact normalized
        val byNorm = gamesByNormalizedTitle()
        for (label in labels) {
            val labelNorm = normalizeTitle(label)
            if (labelNorm.isBlank()) continue
            val exactList = byNorm[labelNorm]
            if (exactList != null) {
                if (exactList.size == 1) return@withContext CatalogTitleMatch(exactList[0], "title-exact")
                AppLog.i(
                    "Catalog",
                    "Ambiguous exact catalog title for '$label' norm='$labelNorm': " +
                        catalogChoicesForLog(exactList)
                )
                return@withContext null
            }
        }

        // 2. Word-list (ordered, prefix with whole-word equality) via first-word bucket
        for (label in labels) {
            val appKey = appCleanKey(label)
            if (appKey.isBlank()) continue
            val verified = verifiedAliasThreadIds[appKey]?.let { gamesById()[it] }
            if (verified != null) return@withContext CatalogTitleMatch(verified, "title-verified-alias")
            val exact = cleanIndex()[appKey].orEmpty()
            if (exact.size == 1) return@withContext CatalogTitleMatch(exact[0], "title-clean-exact")
            if (exact.size > 1) {
                AppLog.i(
                    "Catalog",
                    "Ambiguous clean catalog title for '$label' key='$appKey': " +
                        catalogChoicesForLog(exact)
                )
                return@withContext null
            }
            val cleanCandidates = cleanTitleCandidates(appKey)
            if (cleanCandidates.size == 1) return@withContext CatalogTitleMatch(cleanCandidates[0].second, "title-clean")
            if (cleanCandidates.size > 1) {
                AppLog.i(
                    "Catalog",
                    "Ambiguous clean catalog title for '$label' key='$appKey': " +
                        cleanCandidates.take(12).joinToString(prefix = "[", postfix = "]") {
                            "${it.second.thread_id}:'${it.second.title}' score=${"%.2f".format(it.first)}"
                        }
                )
                return@withContext null
            }
        }

        // 3. Word-list (ordered, prefix with whole-word equality) via first-word bucket
        for (label in labels) {
            val appWords = titleWords(label)
            if (appWords.isEmpty()) continue
            val bucket = wordIndex()[appWords[0]].orEmpty()
            val candidates = bucket.mapNotNull { (gameWords, g) ->
                if (gameWords.isEmpty()) return@mapNotNull null
                val ok = appWords == gameWords ||
                    CatalogRepository.prefixTitleWordsMatch(appWords, gameWords) ||
                    CatalogRepository.prefixTitleWordsMatch(gameWords, appWords)
                if (ok) gameWords to g else null
            }
            if (candidates.size == 1) return@withContext CatalogTitleMatch(candidates[0].second, "title-fuzzy")
            if (candidates.size > 1) {
                AppLog.i(
                    "Catalog",
                    "Ambiguous fuzzy catalog title for '$label' words=${titleWords(label)}: " +
                        catalogChoicesForLog(candidates.map { it.second })
                )
                return@withContext null
            }
        }

        // 4. Acronym match for short folder/launcher labels like "BB_AS".
        if (allowAcronym) {
            for (label in labels) {
                val norm = normalizeTitle(label)
                if (norm.length < 3) continue
                val exactMatches = acronymIndex()[norm].orEmpty()
                val matches = if (exactMatches.isNotEmpty()) {
                    exactMatches
                } else if (norm.length >= 4) {
                    acronymPrefixIndex()[norm].orEmpty()
                } else {
                    emptyList()
                }
                val via = if (exactMatches.isNotEmpty()) "title-acronym" else "title-acronym-prefix"
                if (matches.size == 1) return@withContext CatalogTitleMatch(matches[0], via)
                if (matches.size > 1) {
                    AppLog.i(
                        "Catalog",
                        "Ambiguous acronym catalog title for '$label' norm='$norm': " +
                            catalogChoicesForLog(matches)
                    )
                    return@withContext null
                }
            }
        }
        null
    }

    suspend fun ambiguousTitleMatch(
        appLabels: List<String>,
        allowAcronym: Boolean = true,
    ): CatalogAmbiguousTitleMatch? = withContext(Dispatchers.IO) {
        val labels = appLabels.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (labels.isEmpty()) return@withContext null
        val byNorm = gamesByNormalizedTitle()
        for (label in labels) {
            val labelNorm = normalizeTitle(label)
            if (labelNorm.isBlank()) continue
            val exactList = byNorm[labelNorm].orEmpty()
            if (exactList.size > 1) return@withContext CatalogAmbiguousTitleMatch(exactList, "title-exact")
        }
        for (label in labels) {
            val appWords = titleWords(label)
            if (appWords.isEmpty()) continue
            val candidates = wordIndex()[appWords[0]].orEmpty().mapNotNull { (gameWords, g) ->
                if (gameWords.isEmpty()) return@mapNotNull null
                val ok = appWords == gameWords ||
                    prefixTitleWordsMatch(appWords, gameWords) ||
                    prefixTitleWordsMatch(gameWords, appWords)
                if (ok) g else null
            }.distinctBy { it.thread_id }
            if (candidates.size > 1) return@withContext CatalogAmbiguousTitleMatch(candidates, "title-fuzzy")
        }
        for (label in labels) {
            val appKey = appCleanKey(label)
            if (appKey.isBlank()) continue
            val exact = cleanIndex()[appKey].orEmpty().distinctBy { it.thread_id }
            if (exact.size > 1) return@withContext CatalogAmbiguousTitleMatch(exact, "title-clean-exact")
            val cleanCandidates = cleanTitleCandidates(appKey).map { it.second }.distinctBy { it.thread_id }
            if (cleanCandidates.size > 1) return@withContext CatalogAmbiguousTitleMatch(cleanCandidates, "title-clean")
        }
        if (allowAcronym) {
            for (label in labels) {
                val norm = normalizeTitle(label)
                if (norm.length < 3) continue
                val exactMatches = acronymIndex()[norm].orEmpty()
                val matches = if (exactMatches.isNotEmpty()) {
                    exactMatches
                } else if (norm.length >= 4) {
                    acronymPrefixIndex()[norm].orEmpty()
                } else {
                    emptyList()
                }.distinctBy { it.thread_id }
                val via = if (exactMatches.isNotEmpty()) "title-acronym" else "title-acronym-prefix"
                if (matches.size > 1) return@withContext CatalogAmbiguousTitleMatch(matches, via)
            }
        }
        null
    }

    suspend fun fuzzyMatch(appLabel: String): CatalogGame? =
        bestTitleMatch(listOf(appLabel))?.game

    suspend fun relaxedTitleCandidates(appLabels: List<String>, limit: Int = 12): List<CatalogGame> = withContext(Dispatchers.IO) {
        val labels = appLabels.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (labels.isEmpty()) return@withContext emptyList()
        data class Hit(val score: Double, val game: CatalogGame)
        val hits = HashMap<Int, Hit>()
        fun add(game: CatalogGame, score: Double) {
            val prev = hits[game.thread_id]
            if (prev == null || score > prev.score) hits[game.thread_id] = Hit(score, game)
        }
        for (label in labels) {
            val appKey = appCleanKey(label)
            val rawKey = normalizeTitle(label)
            val appWords = titleWords(label).filter { it.length >= 3 }
            if (appKey.isBlank() && appWords.isEmpty()) continue
            relaxedSuggestionThreadIds[appKey]?.let { gamesById()[it] }?.let { add(it, 1.1) }
            relaxedSuggestionThreadIds.entries.firstOrNull { rawKey.startsWith(it.key) }
                ?.value
                ?.let { gamesById()[it] }
                ?.let { add(it, 1.1) }
            cleanIndex()[appKey].orEmpty().forEach { add(it, 1.0) }
            for ((catalogKey, game) in cleanEntries()) {
                if (appKey.length >= 3) {
                    val exactish = safeCleanTitleMatch(appKey, catalogKey)
                    if (exactish > 0.0) add(game, exactish)
                    val overlap = similarity(appKey, catalogKey)
                    if (overlap >= 0.72 && minOf(appKey.length, catalogKey.length) >= 5) add(game, overlap * 0.9)
                    if (appKey.length >= 5 && (catalogKey.contains(appKey) || appKey.contains(catalogKey))) {
                        val ratio = minOf(appKey.length, catalogKey.length).toDouble() / maxOf(appKey.length, catalogKey.length).toDouble()
                        if (ratio >= 0.35) add(game, 0.74 + ratio * 0.15)
                    }
                }
            }
            if (appWords.isNotEmpty()) {
                for (game in allGames()) {
                    val cw = titleWords(game.title)
                    val matched = appWords.count { aw -> cw.any { it == aw || it.startsWith(aw) || aw.startsWith(it) } }
                    if (matched > 0) add(game, 0.55 + (matched.toDouble() / appWords.size.toDouble()) * 0.25)
                }
            }
        }
        hits.values
            .sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.game.views }.thenByDescending { it.game.ts })
            .map { it.game }
            .take(limit)
    }

    /** Pre-compute titleWords for every catalog entry and bucket by words[0]. */
    private suspend fun wordIndex(): Map<String, List<Pair<List<String>, CatalogGame>>> =
        withContext(Dispatchers.IO) {
            cachedWordIndex ?: run {
                val map = HashMap<String, MutableList<Pair<List<String>, CatalogGame>>>()
                for (g in allGames()) {
                    val w = titleWords(g.title)
                    if (w.isEmpty()) continue
                    map.getOrPut(w[0]) { mutableListOf() }.add(w to g)
                }
                map.also { cachedWordIndex = it }
            }
        }

    private suspend fun cleanIndex(): Map<String, List<CatalogGame>> =
        withContext(Dispatchers.IO) {
            cachedCleanIndex ?: run {
                val map = HashMap<String, MutableList<CatalogGame>>()
                for (g in allGames()) {
                    val key = catalogCleanKey(g.title)
                    if (key.isNotBlank()) map.getOrPut(key) { mutableListOf() }.add(g)
                }
                map.also { cachedCleanIndex = it }
            }
        }

    private suspend fun cleanEntries(): List<Pair<String, CatalogGame>> =
        withContext(Dispatchers.IO) {
            cachedCleanEntries ?: run {
                allGames().mapNotNull { g ->
                    val key = catalogCleanKey(g.title)
                    if (key.isBlank()) null else key to g
                }.also { cachedCleanEntries = it }
            }
        }

    private suspend fun cleanTitleCandidates(appKey: String): List<Pair<Double, CatalogGame>> {
        if (appKey.length < 8) return emptyList()
        val candidates = cleanEntries().mapNotNull { (catalogKey, game) ->
            val score = safeCleanTitleMatch(appKey, catalogKey)
            if (score >= 0.90) score to game else null
        }.sortedWith(
            compareByDescending<Pair<Double, CatalogGame>> { it.first }
                .thenByDescending { it.second.views }
                .thenByDescending { it.second.ts }
        )
        if (candidates.isEmpty()) return emptyList()
        val top = candidates[0].first
        val close = candidates.filter { top - it.first <= 0.03 }.distinctBy { it.second.thread_id }
        return if (close.size == 1) close else close.take(12)
    }

    private suspend fun acronymIndex(): Map<String, List<CatalogGame>> =
        withContext(Dispatchers.IO) {
            cachedAcronymIndex ?: run {
                val map = HashMap<String, MutableList<CatalogGame>>()
                for (g in allGames()) {
                    val a = acronym(titleWords(g.title))
                    if (a.length >= 3) map.getOrPut(a) { mutableListOf() }.add(g)
                }
                map.also { cachedAcronymIndex = it }
            }
        }

    private suspend fun acronymPrefixIndex(): Map<String, List<CatalogGame>> =
        withContext(Dispatchers.IO) {
            cachedAcronymPrefixIndex ?: run {
                val map = HashMap<String, MutableList<CatalogGame>>()
                for (g in allGames()) {
                    val a = acronym(titleWords(g.title))
                    if (a.length >= 4) {
                        for (len in 4..a.length) {
                            map.getOrPut(a.substring(0, len)) { mutableListOf() }.add(g)
                        }
                    }
                }
                map.also { cachedAcronymPrefixIndex = it }
            }
        }

    /**
     * Free-text search over catalog titles. Case-insensitive throughout. Returns up to
     * [limit] games ranked by (highest first):
     *   1000 — exact normalized title (alphanumerics only, lowercase)
     *    950 — lowercase title startsWith full query
     *    900 — lowercase title contains full query as substring
     *    850 — every query token is a prefix of some title token (any order)
     *    750 — at least one query token is a prefix of some title token
     * Ties broken by closeness in title length to the query, then by views desc.
     * Uses a precomputed [SearchEntry] list to avoid regex on every keystroke.
     */
    suspend fun search(query: String, limit: Int = 25): List<CatalogGame> = withContext(Dispatchers.IO) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return@withContext emptyList()
        val qNorm = q.filter { it.isLetterOrDigit() }
        val qTokens = q.split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }
        val qNumberNorm = numberEquivalentKey(q)
        val qNumberTokens = numberEquivalentTokens(rawTitleTokens(q)).filter { it.length >= 2 }

        val entries = searchEntries()

        data class Hit(val score: Int, val lenDiff: Int, val game: CatalogGame)
        val hits = ArrayList<Hit>(64)
        for (e in entries) {
            var score = 0
            if (qNorm.isNotEmpty() && (e.normTitle == qNorm || e.numberNormTitle == qNumberNorm)) score = 1000
            else if (e.lowerTitle.startsWith(q)) score = 950
            else if (qNumberNorm.isNotEmpty() && e.numberNormTitle.startsWith(qNumberNorm)) score = 925
            else if (q.length >= 2 && e.lowerTitle.contains(q)) score = 900
            else if (qNumberNorm.length >= 2 && e.numberNormTitle.contains(qNumberNorm)) score = 875
            else if (qTokens.isNotEmpty() || qNumberTokens.isNotEmpty()) {
                // Require EVERY query token to match a title token (any order). Returning
                // partial-token matches surprised users — typing two words that shouldn't
                // combine gave hits from just the last word. All-or-nothing is clearer.
                fun allTokensPrefix(queryTokens: List<String>, titleTokens: List<String>): Boolean {
                    if (queryTokens.isEmpty()) return false
                    for (qt in queryTokens) {
                        if (!titleTokens.any { it.startsWith(qt) }) return false
                    }
                    return true
                }
                if (allTokensPrefix(qTokens, e.tokens) || allTokensPrefix(qNumberTokens, e.numberTokens)) score = 850
            }
            if (score > 0) {
                hits.add(Hit(score, kotlin.math.abs(e.lowerTitle.length - q.length), e.game))
            }
        }
        hits.sortWith(
            // Alphabetical by lowercase title — user requested simple A→Z ordering
            // within the matched set. (Scoring still gates which entries make it in.)
            compareBy { it.game.title.lowercase() }
        )
        hits.take(limit).map { it.game }
    }

    private suspend fun searchEntries(): List<SearchEntry> = withContext(Dispatchers.IO) {
        cachedSearchEntries ?: run {
            val list = ArrayList<SearchEntry>(25_000)
            for (g in allGames()) {
                val lower = g.title.lowercase()
                val norm = lower.filter { it.isLetterOrDigit() }
                val toks = lower.split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }
                val numberNorm = numberEquivalentKey(g.title)
                val numberToks = numberEquivalentTokens(rawTitleTokens(g.title)).filter { it.length >= 2 }
                list.add(SearchEntry(g, lower, norm, numberNorm, toks, numberToks))
            }
            list.also { cachedSearchEntries = it }
        }
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

    private fun SourceCatalogEntry.toCatalogGame(): CatalogGame {
        val f95ThreadId = if (source == SOURCE_F95ZONE) sourceId.toIntOrNull() else null
        // Deterministic negative synthetic id for non-F95 entries. Kept (not a workaround
        // to remove): CatalogGame.thread_id is an Int identity persisted in AppMapping; a
        // stable hash of "source:sourceId" preserves back-compat for already-saved non-F95
        // mappings. Robust identity is (source, sourceId), also persisted on the mapping.
        val syntheticId = -((("$source:$sourceId".hashCode() and Int.MAX_VALUE).takeIf { it > 0 }) ?: 1)
        return CatalogGame(
            thread_id = f95ThreadId ?: syntheticId,
            title = title,
            creator = developer,
            version = versionText,
            rating = rating,
            views = popularity?.toLong() ?: 0L,
            ts = parseIsoEpochSeconds(modifiedAt ?: publishedAt),
            publishedAt = parseIsoEpochSeconds(publishedAt),
            modifiedAt = parseIsoEpochSeconds(modifiedAt),
            cover = coverUrl,
            source = source,
            sourceId = sourceId,
            sourceUrl = canonicalUrl,
        )
    }

    private fun parseIsoEpochSeconds(value: String?): Long =
        value?.takeIf { it.isNotBlank() }?.let {
            runCatching { java.time.Instant.parse(it).epochSecond }.getOrDefault(0L)
        } ?: 0L

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun countGames(bytes: ByteArray): Int = runCatching {
        openMaybeGzipped(bytes.inputStream()).use { src ->
            json.decodeFromStream<CatalogEnvelope>(src)
        }.games.size
    }.getOrDefault(0)

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun parseSourceEntries(file: File): List<SourceCatalogEntry> = runCatching {
        openMaybeGzipped(file.inputStream()).use { src ->
            json.decodeFromStream<SourceCatalogEnvelope>(src)
        }.entries
    }.onFailure {
        AppLog.e("Catalog", "source catalog parse failed: ${file.name}", it)
    }.getOrDefault(emptyList())

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun countSourceEntries(bytes: ByteArray): Int = runCatching {
        openMaybeGzipped(bytes.inputStream()).use { src ->
            json.decodeFromStream<SourceCatalogEnvelope>(src)
        }.entries.size
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
