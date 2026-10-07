package com.example.f95updater

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun catalogTextSearchKey(source: String, sourceId: String?, threadId: Int): String =
    "$source:${sourceId ?: threadId.toString()}"

internal fun catalogTextSearchKey(source: String, sourceId: String): String =
    "$source:$sourceId"

internal data class CatalogTextSearchDocument(
    val key: String,
    val displayTitle: String,
    val titles: List<String>,
    val sourceEntry: SourceCatalogEntry? = null,
    val legacyGame: CatalogGame? = null,
)

internal enum class CatalogNameMatchKind(val score: Int) {
    Exact(1000),
    Prefix(950),
    Acronym(930),
    Substring(900),
    TokenPrefix(850),
    Typo(800),
}

internal data class CatalogTextSearchScoredMatch(
    val document: CatalogTextSearchDocument,
    val kind: CatalogNameMatchKind,
    val score: Int = kind.score,
    /** Only titles for this result document; never a catalog-wide translation map. */
    val preparedTitles: List<String> = emptyList(),
)

internal data class CatalogIdentityKey(
    val kind: String,
    val normalizedValue: String,
)

/**
 * One deterministic name-search pipeline shared by Catalog, manual matching, and refresh.
 *
 * The catalog payload and prepared titles live in SQLite. FTS finds document keys, then only
 * those documents are loaded and verified; the index intentionally retains no catalog-sized
 * Kotlin collection after construction.
 */
internal class CatalogTextSearchIndex private constructor(
    private val connection: SQLiteConnection,
) : AutoCloseable {
    private data class MatchAssessment(
        val kind: CatalogNameMatchKind,
        val score: Int = kind.score,
    )

    private val connectionLock = Any()
    @Volatile private var shortQueryScanCount = 0

    fun search(
        query: String,
        checkCancelled: () -> Unit = {},
    ): List<CatalogTextSearchScoredMatch> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val lower = trimmed.lowercase()
        val queryNormalizedKeys = normalizedSearchKeys(lower)
        val queryCleanKey = CatalogRepository.appCleanKey(lower)
        val queryNumberKey = CatalogRepository.numberEquivalentKey(lower)
        val queryTokens = directTokens(lower)
        val intersectionTokens = queryTokens.filter { token ->
            token.codePointCount(0, token.length) >= 3
        }
        val acronymQuery = looksLikeAcronymQuery(trimmed)

        val candidateKeys = linkedSetOf<String>()
        fun offer(keys: Collection<String>) {
            candidateKeys.addAll(keys)
        }

        val candidateQueries = linkedSetOf(lower)
        candidateQueries += queryNormalizedKeys
        queryCleanKey.takeIf(String::isNotEmpty)?.let(candidateQueries::add)
        queryNumberKey.takeIf(String::isNotEmpty)?.let(candidateQueries::add)
        candidateQueries.forEach { candidateQuery ->
            offer(substringCandidateKeys(candidateQuery, checkCancelled))
        }

        if (intersectionTokens.isNotEmpty() &&
            (queryTokens.size > 1 || lower.any { !it.isLetterOrDigit() })
        ) {
            offer(intersectTokenCandidateKeys(intersectionTokens, checkCancelled))
        }

        checkCancelled()
        if (candidateKeys.isEmpty()) return emptyList()
        return loadAndVerifyCandidates(
            candidateKeys = candidateKeys,
            query = lower,
            queryNormalizedKeys = queryNormalizedKeys,
            queryCleanKey = queryCleanKey,
            queryNumberKey = queryNumberKey,
            queryTokens = queryTokens,
            acronymQuery = acronymQuery,
            checkCancelled = checkCancelled,
        )
    }

    /**
     * Strict final fallback for refresh-time matching. Candidate retrieval requires at least two
     * exact shared tokens, then verification permits exactly one Damerau edit in one long token.
     */
    fun searchTypoRescue(
        query: String,
        checkCancelled: () -> Unit = {},
    ): List<CatalogTextSearchScoredMatch> {
        val queryTokens = directTokens(query.trim().lowercase())
        if (queryTokens.size < MIN_TYPO_QUERY_TOKENS) return emptyList()
        val searchableTokens = queryTokens
            .filter { it.codePointCount(0, it.length) >= MIN_FTS_TOKEN_CODEPOINTS }
            .distinct()
        if (searchableTokens.size < MIN_SHARED_TYPO_TOKENS) return emptyList()

        val occurrenceCounts = LinkedHashMap<String, Int>()
        searchableTokens.forEach { token ->
            checkCancelled()
            queryFts(token).forEach { key ->
                occurrenceCounts[key] = (occurrenceCounts[key] ?: 0) + 1
            }
        }
        val requiredSharedTokens = maxOf(MIN_SHARED_TYPO_TOKENS, searchableTokens.size - 1)
        val candidateKeys = occurrenceCounts
            .filterValues { it >= requiredSharedTokens }
            .keys
            .take(MAX_TYPO_CANDIDATES)
            .toSet()
        if (candidateKeys.isEmpty()) return emptyList()

        return loadCandidateMatches(candidateKeys, checkCancelled) { titles ->
            titles.asSequence()
                .mapNotNull { title -> assessSingleTypoTitle(title, queryTokens) }
                .maxByOrNull { it.score }
        }
    }

    fun searchIdentity(
        kind: CatalogIdentityKind,
        normalizedValue: String,
        checkCancelled: () -> Unit = {},
    ): List<CatalogTextSearchScoredMatch> {
        val candidateKeys = synchronized(connectionLock) {
            buildSet {
                connection.prepare(
                    """
                    SELECT doc_key
                    FROM search_identifiers
                    WHERE kind = ? AND normalized_value = ?
                    ORDER BY doc_key
                    """.trimIndent(),
                ).use { statement ->
                    statement.bindText(1, kind.wireValue)
                    statement.bindText(2, normalizedValue)
                    while (statement.step()) {
                        checkCancelled()
                        add(statement.getText(0))
                    }
                }
            }
        }
        if (candidateKeys.isEmpty()) return emptyList()
        return loadCandidateMatches(candidateKeys, checkCancelled) {
            MatchAssessment(CatalogNameMatchKind.Exact)
        }
    }

    internal fun shortQueryScanCountForTest(): Int = shortQueryScanCount

    private fun substringCandidateKeys(
        query: String,
        checkCancelled: () -> Unit,
    ): List<String> {
        if (query.isEmpty()) return emptyList()
        return if (query.codePointCount(0, query.length) in 1..2) {
            queryShortSubstring(query, checkCancelled)
        } else {
            queryFts(query)
        }
    }

    private fun intersectTokenCandidateKeys(
        tokens: List<String>,
        checkCancelled: () -> Unit,
    ): List<String> {
        val postings = tokens
            .map { token ->
                checkCancelled()
                substringCandidateKeys(token, checkCancelled)
            }
            .sortedBy { it.size }
        if (postings.isEmpty() || postings.first().isEmpty()) return emptyList()
        var intersection = postings.first()
        for (postingIndex in 1 until postings.size) {
            val present = postings[postingIndex].toHashSet()
            intersection = intersection.filterTo(ArrayList()) { key ->
                checkCancelled()
                key in present
            }
            if (intersection.isEmpty()) break
        }
        return intersection
    }

    private fun queryShortSubstring(
        query: String,
        checkCancelled: () -> Unit,
    ): List<String> = synchronized(connectionLock) {
        val startedAt = System.nanoTime()
        val keys = ArrayList<String>()
        var matchingRows = 0
        shortQueryScanCount++
        checkCancelled()
        connection.prepare(
            """
            SELECT names_fts.doc_key
            FROM names_fts
            JOIN search_documents ON search_documents.doc_key = names_fts.doc_key
            WHERE instr(names_fts.name, ?) > 0
            GROUP BY names_fts.doc_key
            ORDER BY MIN(search_documents.document_order)
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, query)
            while (statement.step()) {
                if (matchingRows and CANCELLATION_CHECK_MASK == 0) checkCancelled()
                matchingRows++
                keys += statement.getText(0)
            }
        }
        checkCancelled()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        if (elapsedMs >= 50L) {
            AppLog.i(
                "CatalogSearch",
                "Short SQLite scan codePoints=${query.codePointCount(0, query.length)} " +
                    "matchingRows=$matchingRows results=${keys.size} took ${elapsedMs}ms",
            )
        }
        keys
    }

    private fun queryFts(query: String): List<String> = synchronized(connectionLock) {
        buildList {
            connection.prepare(
                """
                SELECT names_fts.doc_key
                FROM names_fts
                JOIN search_documents ON search_documents.doc_key = names_fts.doc_key
                WHERE names_fts MATCH ?
                GROUP BY names_fts.doc_key
                ORDER BY MIN(search_documents.document_order)
                """.trimIndent(),
            ).use { statement ->
                statement.bindText(1, ftsTrigramQuery(query))
                while (statement.step()) add(statement.getText(0))
            }
        }
    }

    private fun loadAndVerifyCandidates(
        candidateKeys: Set<String>,
        query: String,
        queryNormalizedKeys: Set<String>,
        queryCleanKey: String,
        queryNumberKey: String,
        queryTokens: List<String>,
        acronymQuery: Boolean,
        checkCancelled: () -> Unit,
    ): List<CatalogTextSearchScoredMatch> =
        loadCandidateMatches(candidateKeys, checkCancelled) { titles ->
            titles
                .mapNotNull { title ->
                    assessTitle(
                        title = title,
                        query = query,
                        queryNormalizedKeys = queryNormalizedKeys,
                        queryCleanKey = queryCleanKey,
                        queryNumberKey = queryNumberKey,
                        queryTokens = queryTokens,
                        acronymQuery = acronymQuery,
                    )
                }
                .maxWithOrNull(compareBy<MatchAssessment> { it.score }.thenBy { it.kind.ordinal })
        }

    private fun loadCandidateMatches(
        candidateKeys: Set<String>,
        checkCancelled: () -> Unit,
        assessTitles: (List<String>) -> MatchAssessment?,
    ): List<CatalogTextSearchScoredMatch> = synchronized(connectionLock) {
        connection.execSQL(
            "CREATE TEMP TABLE IF NOT EXISTS search_candidate_keys(" +
                "doc_key TEXT PRIMARY KEY, candidate_order INTEGER NOT NULL) WITHOUT ROWID",
        )
        connection.execSQL("DELETE FROM search_candidate_keys")
        try {
            connection.prepare(
                "INSERT INTO search_candidate_keys(doc_key, candidate_order) VALUES (?, ?)",
            ).use { statement ->
                candidateKeys.forEachIndexed { index, key ->
                    if (index and CANCELLATION_CHECK_MASK == 0) checkCancelled()
                    statement.reset()
                    statement.clearBindings()
                    statement.bindText(1, key)
                    statement.bindInt(2, index)
                    statement.step()
                }
            }
            val matches = ArrayList<CatalogTextSearchScoredMatch>()
            connection.prepare(
                """
                SELECT d.doc_key, d.display_title, d.source_entry_json, d.legacy_game_json,
                       t.prepared_title
                FROM search_candidate_keys c
                JOIN search_documents d ON d.doc_key = c.doc_key
                LEFT JOIN search_titles t ON t.doc_key = d.doc_key
                ORDER BY c.candidate_order, t.title_order
                """.trimIndent(),
            ).use { statement ->
                var verified = 0
                var currentKey: String? = null
                var currentDocument: CatalogTextSearchDocument? = null
                val currentTitles = ArrayList<String>()

                fun verifyCurrent() {
                    val document = currentDocument ?: return
                    if (verified and CANCELLATION_CHECK_MASK == 0) checkCancelled()
                    verified++
                    val best = assessTitles(currentTitles) ?: return
                    matches += CatalogTextSearchScoredMatch(
                        document = document,
                        kind = best.kind,
                        score = best.score,
                        preparedTitles = currentTitles.toList(),
                    )
                }

                while (statement.step()) {
                    val key = statement.getText(0)
                    if (key != currentKey) {
                        verifyCurrent()
                        currentKey = key
                        currentTitles.clear()
                        currentDocument = CatalogTextSearchDatabaseStore.decodeDocument(
                            key = key,
                            displayTitle = statement.getText(1),
                            sourceEntryJson = statement.getText(2),
                            legacyGameJson = statement.getText(3),
                        )
                    }
                    statement.getText(4)?.let(currentTitles::add)
                }
                verifyCurrent()
            }
            matches
        } finally {
            connection.execSQL("DELETE FROM search_candidate_keys")
        }
    }

    private fun assessSingleTypoTitle(
        title: String,
        queryTokens: List<String>,
    ): MatchAssessment? {
        val titleTokens = directTokens(title.lowercase())
        if (titleTokens.size != queryTokens.size) return null
        var differingTokens = 0
        for (index in queryTokens.indices) {
            val queryToken = queryTokens[index]
            val titleToken = titleTokens[index]
            if (queryToken == titleToken) continue
            if (queryToken.codePointCount(0, queryToken.length) < MIN_TYPO_TOKEN_CODEPOINTS ||
                titleToken.codePointCount(0, titleToken.length) < MIN_TYPO_TOKEN_CODEPOINTS ||
                !isSingleDamerauEditApart(queryToken, titleToken)
            ) {
                return null
            }
            differingTokens++
            if (differingTokens > 1) return null
        }
        return MatchAssessment(CatalogNameMatchKind.Typo).takeIf { differingTokens == 1 }
    }

    private fun assessTitle(
        title: String,
        query: String,
        queryNormalizedKeys: Set<String>,
        queryCleanKey: String,
        queryNumberKey: String,
        queryTokens: List<String>,
        acronymQuery: Boolean,
    ): MatchAssessment? {
        val lowerTitle = title.lowercase()
        if (lowerTitle == query) return MatchAssessment(CatalogNameMatchKind.Exact)
        if (normalizedSearchKeys(lowerTitle).any(queryNormalizedKeys::contains)) {
            return MatchAssessment(CatalogNameMatchKind.Exact)
        }
        if (queryCleanKey.isNotEmpty() &&
            CatalogRepository.catalogCleanKey(lowerTitle) == queryCleanKey
        ) {
            return MatchAssessment(CatalogNameMatchKind.Exact)
        }

        val titleNumberKey = CatalogRepository.numberEquivalentKey(lowerTitle)
        if (queryNumberKey.isNotEmpty() && titleNumberKey == queryNumberKey) {
            return MatchAssessment(CatalogNameMatchKind.Exact)
        }
        if (lowerTitle.startsWith(query)) return MatchAssessment(CatalogNameMatchKind.Prefix)
        if (queryNumberKey.isNotEmpty() && titleNumberKey.startsWith(queryNumberKey)) {
            return MatchAssessment(CatalogNameMatchKind.Prefix, 925)
        }
        if (acronymQuery &&
            CatalogRepository.acronym(CatalogRepository.titleWords(lowerTitle)) ==
            CatalogRepository.normalizeTitle(query)
        ) {
            return MatchAssessment(CatalogNameMatchKind.Acronym)
        }
        if (lowerTitle.contains(query)) return MatchAssessment(CatalogNameMatchKind.Substring)
        if (queryNumberKey.length >= 2 && titleNumberKey.contains(queryNumberKey)) {
            return MatchAssessment(CatalogNameMatchKind.Substring, 875)
        }
        if (queryTokens.isNotEmpty() && queryTokens.all { titleHasTokenPrefix(lowerTitle, it) }) {
            return MatchAssessment(CatalogNameMatchKind.TokenPrefix)
        }
        return null
    }

    override fun close() {
        synchronized(connectionLock) {
            connection.close()
        }
    }

    companion object {
        private const val CANCELLATION_CHECK_MASK = 0x7F
        private const val MIN_TYPO_QUERY_TOKENS = 3
        private const val MIN_SHARED_TYPO_TOKENS = 2
        private const val MIN_TYPO_TOKEN_CODEPOINTS = 6
        private const val MIN_FTS_TOKEN_CODEPOINTS = 3
        private const val MAX_TYPO_CANDIDATES = 500
        private val DATABASE_LOCKS = ConcurrentHashMap<String, Any>()

        fun build(
            sourceDocuments: List<CatalogTextSearchDocument>,
            databaseFile: File? = null,
            checkCancelled: () -> Unit = {},
        ): CatalogTextSearchIndex {
            val startedAt = System.currentTimeMillis()
            CatalogMemoryDiagnostics.log(
                phase = "fts_prepare_start",
                detail = "documents=${sourceDocuments.size}",
            )
            val contentSignature = CatalogTextSearchDatabaseStore.contentSignature(
                sourceDocuments,
                checkCancelled,
            )
            CatalogMemoryDiagnostics.log(
                phase = "fts_prepare_complete",
                startedAtMs = startedAt,
                detail = "documents=${sourceDocuments.size}",
            )
            val databaseStartedAt = System.currentTimeMillis()
            val connection = openSearchDatabase(
                databaseFile = databaseFile,
                sourceDocuments = sourceDocuments,
                contentSignature = contentSignature,
                checkCancelled = checkCancelled,
            )
            CatalogMemoryDiagnostics.log(
                phase = "fts_database_open_complete",
                startedAtMs = databaseStartedAt,
                detail = "documents=${sourceDocuments.size}",
            )
            return CatalogTextSearchIndex(connection)
        }

        internal fun open(databaseFile: File): CatalogTextSearchIndex {
            require(databaseFile.isFile) {
                "Catalog search database does not exist: ${databaseFile.absolutePath}"
            }
            val connection = BundledSQLiteDriver().open(
                databaseFile.absolutePath,
                SQLITE_OPEN_READWRITE or SQLITE_OPEN_FULLMUTEX,
            )
            try {
                CatalogTextSearchDatabaseStore.configure(connection)
                check(CatalogTextSearchDatabaseStore.isCompatible(connection)) {
                    "Catalog search database schema is incompatible"
                }
                return CatalogTextSearchIndex(connection)
            } catch (error: Throwable) {
                connection.close()
                throw error
            }
        }

        private fun openSearchDatabase(
            databaseFile: File?,
            sourceDocuments: List<CatalogTextSearchDocument>,
            contentSignature: String,
            checkCancelled: () -> Unit,
        ): SQLiteConnection {
            if (databaseFile == null) {
                return BundledSQLiteDriver().open(
                    ":memory:",
                    SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE or SQLITE_OPEN_FULLMUTEX,
                ).also { connection ->
                    CatalogTextSearchDatabaseStore.configure(connection)
                    CatalogTextSearchDatabaseStore.createSchema(connection)
                    updateDatabase(connection, sourceDocuments, contentSignature, checkCancelled)
                }
            }
            val lock = DATABASE_LOCKS.getOrPut(databaseFile.absolutePath) { Any() }
            return synchronized(lock) {
                openPersistentSearchDatabase(
                    databaseFile,
                    sourceDocuments,
                    contentSignature,
                    checkCancelled,
                )
            }
        }

        private fun openPersistentSearchDatabase(
            databaseFile: File,
            sourceDocuments: List<CatalogTextSearchDocument>,
            contentSignature: String,
            checkCancelled: () -> Unit,
        ): SQLiteConnection {
            databaseFile.parentFile?.mkdirs()
            if (databaseFile.isFile) {
                val existing = try {
                    BundledSQLiteDriver().open(
                        databaseFile.absolutePath,
                        SQLITE_OPEN_READWRITE or SQLITE_OPEN_FULLMUTEX,
                    )
                } catch (error: Exception) {
                    AppLog.w(
                        "CatalogSearch",
                        "Could not open catalog name-search database; rebuilding it",
                        error,
                    )
                    null
                }
                if (existing != null) {
                    try {
                        CatalogTextSearchDatabaseStore.configure(existing)
                        if (CatalogTextSearchDatabaseStore.isCompatible(existing)) {
                            if (CatalogTextSearchDatabaseStore.readContentSignature(existing) != contentSignature) {
                                updateDatabase(existing, sourceDocuments, contentSignature, checkCancelled)
                            }
                            return existing
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        existing.close()
                        throw cancelled
                    } catch (error: Exception) {
                        AppLog.w(
                            "CatalogSearch",
                            "Discarding unreadable catalog name-search database",
                            error,
                        )
                    }
                    existing.close()
                }
            }
            return buildPersistentDatabase(
                databaseFile,
                sourceDocuments,
                contentSignature,
                checkCancelled,
            )
        }

        private fun buildPersistentDatabase(
            databaseFile: File,
            sourceDocuments: List<CatalogTextSearchDocument>,
            contentSignature: String,
            checkCancelled: () -> Unit,
        ): SQLiteConnection {
            val temporary = File(databaseFile.parentFile, "${databaseFile.name}.tmp")
            deleteDatabaseFiles(temporary)
            try {
                BundledSQLiteDriver().open(
                    temporary.absolutePath,
                    SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE or SQLITE_OPEN_FULLMUTEX,
                ).use { connection ->
                    CatalogTextSearchDatabaseStore.configure(connection)
                    CatalogTextSearchDatabaseStore.createSchema(connection)
                    updateDatabase(connection, sourceDocuments, contentSignature, checkCancelled)
                }
                checkCancelled()
                replaceDatabase(temporary, databaseFile)
            } catch (error: Throwable) {
                deleteDatabaseFiles(temporary)
                throw error
            }
            return BundledSQLiteDriver().open(
                databaseFile.absolutePath,
                SQLITE_OPEN_READWRITE or SQLITE_OPEN_FULLMUTEX,
            ).also(CatalogTextSearchDatabaseStore::configure)
        }

        private fun updateDatabase(
            connection: SQLiteConnection,
            sourceDocuments: List<CatalogTextSearchDocument>,
            contentSignature: String,
            checkCancelled: () -> Unit,
        ) {
            val startedAt = System.currentTimeMillis()
            CatalogMemoryDiagnostics.log(
                phase = "fts_insert_start",
                detail = "documents=${sourceDocuments.size}",
            )
            connection.execSQL("BEGIN IMMEDIATE")
            try {
                CatalogTextSearchDatabaseStore.beginUpdate(connection).use { writer ->
                    sourceDocuments.forEachIndexed { index, document ->
                        if (index and CANCELLATION_CHECK_MASK == 0) checkCancelled()
                        writer.upsert(document, index)
                    }
                    writer.deleteUnseen()
                }
                CatalogTextSearchDatabaseStore.writeContentSignature(connection, contentSignature)
                connection.execSQL("COMMIT")
            } catch (error: Throwable) {
                try {
                    connection.execSQL("ROLLBACK")
                } catch (rollbackError: Exception) {
                    error.addSuppressed(rollbackError)
                }
                throw error
            }
            CatalogMemoryDiagnostics.log(
                phase = "fts_insert_complete",
                startedAtMs = startedAt,
                detail = "documents=${sourceDocuments.size}",
            )
        }

        private fun replaceDatabase(temporary: File, target: File) {
            val backup = File(target.parentFile, "${target.name}.bak")
            deleteDatabaseFiles(backup)
            if (target.exists() && !target.renameTo(backup)) {
                throw IllegalStateException("Could not replace the catalog name-search database")
            }
            if (!temporary.renameTo(target)) {
                backup.renameTo(target)
                throw IllegalStateException("Could not finalize the catalog name-search database")
            }
            deleteDatabaseFiles(backup)
        }

        private fun deleteDatabaseFiles(file: File) {
            listOf(file, File("${file.path}-journal"), File("${file.path}-wal"), File("${file.path}-shm"))
                .forEach { candidate ->
                    if (candidate.exists() && !candidate.delete()) {
                        AppLog.w("CatalogSearch", "Could not delete ${candidate.name}")
                    }
                }
        }
    }
}

/**
 * Reusable schema and incremental writer for catalog-name search data. Callers own the
 * transaction: start with [beginUpdate], upsert documents as they are produced, then call
 * [CatalogTextSearchDatabaseWriter.deleteUnseen].
 */
internal object CatalogTextSearchDatabaseStore {
    internal const val signatureGeneration = "search-v8-identities-v1"
    private const val SCHEMA_VERSION = "8"
    private const val META_SCHEMA = "schema"
    private const val META_CONTENT_SIGNATURE = "content_signature"
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun configure(connection: SQLiteConnection) {
        connection.execSQL("PRAGMA temp_store=FILE")
        connection.execSQL("PRAGMA cache_size=-2000")
    }

    fun createSchema(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE search_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        connection.execSQL(
            """
            CREATE TABLE search_documents(
                doc_key TEXT PRIMARY KEY,
                document_order INTEGER NOT NULL,
                display_title TEXT NOT NULL,
                source_entry_json TEXT NOT NULL,
                legacy_game_json TEXT NOT NULL,
                payload_fingerprint TEXT NOT NULL,
                name_fingerprint TEXT NOT NULL
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TABLE search_titles(
                doc_key TEXT NOT NULL,
                title_order INTEGER NOT NULL,
                prepared_title TEXT NOT NULL,
                PRIMARY KEY(doc_key, title_order)
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE VIRTUAL TABLE names_fts USING fts5(" +
                "name, doc_key UNINDEXED, tokenize='trigram', detail='none')",
        )
        connection.execSQL(
            """
            CREATE TABLE search_identifiers(
                kind TEXT NOT NULL,
                normalized_value TEXT NOT NULL,
                doc_key TEXT NOT NULL,
                PRIMARY KEY(kind, normalized_value, doc_key)
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX search_identifiers_lookup " +
                "ON search_identifiers(kind, normalized_value)",
        )
        connection.execSQL(
            """
            CREATE TABLE search_document_groups(
                doc_key TEXT PRIMARY KEY,
                agm_group_id TEXT NOT NULL
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        writeMeta(connection, META_SCHEMA, SCHEMA_VERSION)
    }

    fun isCompatible(connection: SQLiteConnection): Boolean {
        if (readMeta(connection, META_SCHEMA) != SCHEMA_VERSION) return false
        val requiredTables = setOf(
            "search_documents",
            "search_titles",
            "names_fts",
            "search_identifiers",
            "search_document_groups",
        )
        val presentTables = connection.prepare(
            "SELECT name FROM sqlite_master WHERE name IN (?, ?, ?, ?, ?)",
        ).use { statement ->
            requiredTables.forEachIndexed { index, table ->
                statement.bindText(index + 1, table)
            }
            buildSet {
                while (statement.step()) add(statement.getText(0))
            }
        }
        return presentTables == requiredTables
    }

    fun readContentSignature(connection: SQLiteConnection): String? =
        readMeta(connection, META_CONTENT_SIGNATURE)

    fun writeContentSignature(connection: SQLiteConnection, signature: String) {
        writeMeta(connection, META_CONTENT_SIGNATURE, signature)
    }

    fun beginUpdate(
        connection: SQLiteConnection,
        freshBuild: Boolean = false,
    ): CatalogTextSearchDatabaseWriter {
        connection.execSQL(
            "CREATE TEMP TABLE IF NOT EXISTS search_seen_keys(" +
                "doc_key TEXT PRIMARY KEY) WITHOUT ROWID",
        )
        connection.execSQL("DELETE FROM search_seen_keys")
        return CatalogTextSearchDatabaseWriter(connection, freshBuild)
    }

    fun contentSignature(
        sourceDocuments: List<CatalogTextSearchDocument>,
        checkCancelled: () -> Unit,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        sourceDocuments.forEachIndexed { index, document ->
            if (index and CANCELLATION_CHECK_MASK == 0) checkCancelled()
            val prepared = prepare(document)
            updateDigest(digest, prepared.key)
            updateDigest(digest, prepared.displayTitle)
            updateDigest(digest, prepared.sourceEntryJson)
            updateDigest(digest, prepared.legacyGameJson)
            prepared.titles.forEach { updateDigest(digest, it) }
            prepared.identifiers.forEach {
                updateDigest(digest, it.kind)
                updateDigest(digest, it.normalizedValue)
            }
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun decodeDocument(
        key: String,
        displayTitle: String,
        sourceEntryJson: String,
        legacyGameJson: String,
    ): CatalogTextSearchDocument = CatalogTextSearchDocument(
        key = key,
        displayTitle = displayTitle,
        titles = emptyList(),
        sourceEntry = sourceEntryJson.takeIf(String::isNotEmpty)?.let {
            json.decodeFromString<SourceCatalogEntry>(it)
        },
        legacyGame = legacyGameJson.takeIf(String::isNotEmpty)?.let {
            json.decodeFromString<CatalogGame>(it)
        },
    )

    internal fun prepare(document: CatalogTextSearchDocument): PreparedCatalogTextSearchDocument {
        val titles = ArrayList<String>(document.titles.size)
        document.titles.forEach { rawTitle ->
            val title = rawTitle.trim()
            if (title.isNotEmpty() && titles.none { it.equals(title, ignoreCase = true) }) {
                titles += title
            }
        }
        val sourceEntryJson = document.sourceEntry?.let(json::encodeToString).orEmpty()
        val legacyGameJson = document.legacyGame?.let(json::encodeToString).orEmpty()
        val identifiers = buildList {
            document.sourceEntry?.productCodes.orEmpty().forEach { raw ->
                raw.trim().uppercase().takeIf(String::isNotEmpty)?.let { code ->
                    add(CatalogIdentityKey(CatalogIdentityKind.ProductCode.wireValue, code))
                }
            }
            document.sourceEntry?.downloadAliases.orEmpty().forEach { raw ->
                normalizeDownloadAlias(raw)?.let { alias ->
                    add(CatalogIdentityKey(CatalogIdentityKind.DownloadAlias.wireValue, alias))
                }
            }
        }.distinct()
        return PreparedCatalogTextSearchDocument(
            key = document.key,
            displayTitle = document.displayTitle,
            titles = titles,
            sourceEntryJson = sourceEntryJson,
            legacyGameJson = legacyGameJson,
            agmGroupId = document.sourceEntry?.agmGroupId?.trim().orEmpty(),
            identifiers = identifiers,
        )
    }

    private fun readMeta(connection: SQLiteConnection, key: String): String? =
        connection.prepare("SELECT value FROM search_meta WHERE key = ?").use { statement ->
            statement.bindText(1, key)
            if (statement.step()) statement.getText(0) else null
        }

    private fun writeMeta(connection: SQLiteConnection, key: String, value: String) {
        connection.prepare(
            "INSERT OR REPLACE INTO search_meta(key, value) VALUES (?, ?)",
        ).use { statement ->
            statement.bindText(1, key)
            statement.bindText(2, value)
            statement.step()
        }
    }

    internal fun nameFingerprint(document: PreparedCatalogTextSearchDocument): String =
        digestOf(
            document.key,
            *(document.titles + document.identifiers.flatMap { listOf(it.kind, it.normalizedValue) }).toTypedArray(),
        )

    internal fun payloadFingerprint(document: PreparedCatalogTextSearchDocument): String =
        digestOf(
            document.key,
            document.displayTitle,
            document.sourceEntryJson,
            document.legacyGameJson,
        )

    internal fun insertNameVariants(
        statement: SQLiteStatement,
        name: String,
        documentKey: String,
    ) {
        nameVariants(name).forEach { variant ->
            statement.reset()
            statement.clearBindings()
            statement.bindText(1, variant)
            statement.bindText(2, documentKey)
            statement.step()
        }
    }

    private fun digestOf(first: String, vararg values: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        updateDigest(digest, first)
        values.forEach { updateDigest(digest, it) }
        val bytes = digest.digest()
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = HEX_DIGITS[v ushr 4]
            chars[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
        }
        return String(chars)
    }

    private fun updateDigest(digest: MessageDigest, value: String) {
        digest.update(value.toByteArray(Charsets.UTF_8))
        digest.update(0)
    }

    internal data class PreparedCatalogTextSearchDocument(
        val key: String,
        val displayTitle: String,
        val titles: List<String>,
        val sourceEntryJson: String,
        val legacyGameJson: String,
        val agmGroupId: String,
        val identifiers: List<CatalogIdentityKey>,
    )
}

/**
 * Per-connection writer. It accepts one document at a time so a catalog-row producer can update
 * the name-search tables without first retaining a second catalog collection.
 */
internal class CatalogTextSearchDatabaseWriter internal constructor(
    private val connection: SQLiteConnection,
    private val freshBuild: Boolean = false,
) : AutoCloseable {
    private val selectExisting = connection.prepare(
        """
        SELECT document_order, payload_fingerprint, name_fingerprint
        FROM search_documents WHERE doc_key = ?
        """.trimIndent(),
    )
    private val markSeen = connection.prepare(
        "INSERT OR REPLACE INTO search_seen_keys(doc_key) VALUES (?)",
    )
    private val insertDocument = connection.prepare(
        """
        INSERT INTO search_documents(
            doc_key, document_order, display_title, source_entry_json, legacy_game_json,
            payload_fingerprint, name_fingerprint
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
    )
    private val updateDocument = connection.prepare(
        """
        UPDATE search_documents
        SET document_order = ?, display_title = ?, source_entry_json = ?,
            legacy_game_json = ?, payload_fingerprint = ?
        WHERE doc_key = ?
        """.trimIndent(),
    )
    private val updateNameFingerprint = connection.prepare(
        "UPDATE search_documents SET name_fingerprint = ? WHERE doc_key = ?",
    )
    private val deleteFtsNames = connection.prepare(
        "DELETE FROM names_fts WHERE doc_key = ?",
    )
    private val deletePreparedTitles = connection.prepare(
        "DELETE FROM search_titles WHERE doc_key = ?",
    )
    private val deleteIdentifiers = connection.prepare(
        "DELETE FROM search_identifiers WHERE doc_key = ?",
    )
    private val insertPreparedTitle = connection.prepare(
        "INSERT INTO search_titles(doc_key, title_order, prepared_title) VALUES (?, ?, ?)",
    )
    private val insertFtsName = connection.prepare(
        "INSERT INTO names_fts(name, doc_key) VALUES (?, ?)",
    )
    private val insertIdentifier = connection.prepare(
        "INSERT INTO search_identifiers(kind, normalized_value, doc_key) VALUES (?, ?, ?)",
    )
    private val upsertDocumentGroup = connection.prepare(
        "INSERT OR REPLACE INTO search_document_groups(doc_key, agm_group_id) VALUES (?, ?)",
    )

    fun upsert(document: CatalogTextSearchDocument, documentOrder: Int) {
        val prepared = CatalogTextSearchDatabaseStore.prepare(document)
        val nameFingerprint = CatalogTextSearchDatabaseStore.nameFingerprint(prepared)
        val payloadFingerprint = CatalogTextSearchDatabaseStore.payloadFingerprint(prepared)
        if (freshBuild) {
            // Empty target: insert directly, skipping the existing-lookup, seen-key marking,
            // and the pre-delete of names/titles (there is nothing to delete yet).
            insertDocument.reset()
            insertDocument.clearBindings()
            insertDocument.bindText(1, prepared.key)
            insertDocument.bindInt(2, documentOrder)
            insertDocument.bindText(3, prepared.displayTitle)
            insertDocument.bindText(4, prepared.sourceEntryJson)
            insertDocument.bindText(5, prepared.legacyGameJson)
            insertDocument.bindText(6, payloadFingerprint)
            insertDocument.bindText(7, nameFingerprint)
            insertDocument.step()
            upsertGroup(prepared)
            insertNamesAndTitles(prepared)
            return
        }
        selectExisting.reset()
        selectExisting.clearBindings()
        selectExisting.bindText(1, prepared.key)
        val existing =
            if (selectExisting.step()) {
                ExistingDocument(
                    documentOrder = selectExisting.getLong(0).toInt(),
                    payloadFingerprint = selectExisting.getText(1),
                    nameFingerprint = selectExisting.getText(2),
                )
            } else {
                null
            }
        markSeen.reset()
        markSeen.clearBindings()
        markSeen.bindText(1, prepared.key)
        markSeen.step()

        if (existing == null) {
            insertDocument.reset()
            insertDocument.clearBindings()
            insertDocument.bindText(1, prepared.key)
            insertDocument.bindInt(2, documentOrder)
            insertDocument.bindText(3, prepared.displayTitle)
            insertDocument.bindText(4, prepared.sourceEntryJson)
            insertDocument.bindText(5, prepared.legacyGameJson)
            insertDocument.bindText(6, payloadFingerprint)
            insertDocument.bindText(7, nameFingerprint)
            insertDocument.step()
            upsertGroup(prepared)
            replaceNames(prepared)
            return
        }
        upsertGroup(prepared)
        if (existing.documentOrder != documentOrder ||
            existing.payloadFingerprint != payloadFingerprint
        ) {
            updateDocument.reset()
            updateDocument.clearBindings()
            updateDocument.bindInt(1, documentOrder)
            updateDocument.bindText(2, prepared.displayTitle)
            updateDocument.bindText(3, prepared.sourceEntryJson)
            updateDocument.bindText(4, prepared.legacyGameJson)
            updateDocument.bindText(5, payloadFingerprint)
            updateDocument.bindText(6, prepared.key)
            updateDocument.step()
        }
        if (existing.nameFingerprint != nameFingerprint) {
            updateNameFingerprint.reset()
            updateNameFingerprint.clearBindings()
            updateNameFingerprint.bindText(1, nameFingerprint)
            updateNameFingerprint.bindText(2, prepared.key)
            updateNameFingerprint.step()
            replaceNames(prepared)
        }
    }

    fun deleteUnseen() {
        connection.execSQL(
            """
            DELETE FROM names_fts
            WHERE doc_key IN (
                SELECT d.doc_key FROM search_documents d
                WHERE NOT EXISTS (
                    SELECT 1 FROM search_seen_keys s WHERE s.doc_key = d.doc_key
                )
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            DELETE FROM search_titles
            WHERE doc_key IN (
                SELECT d.doc_key FROM search_documents d
                WHERE NOT EXISTS (
                    SELECT 1 FROM search_seen_keys s WHERE s.doc_key = d.doc_key
                )
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            DELETE FROM search_identifiers
            WHERE doc_key IN (
                SELECT d.doc_key FROM search_documents d
                WHERE NOT EXISTS (
                    SELECT 1 FROM search_seen_keys s WHERE s.doc_key = d.doc_key
                )
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            DELETE FROM search_document_groups
            WHERE doc_key IN (
                SELECT d.doc_key FROM search_documents d
                WHERE NOT EXISTS (
                    SELECT 1 FROM search_seen_keys s WHERE s.doc_key = d.doc_key
                )
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            DELETE FROM search_documents
            WHERE NOT EXISTS (
                SELECT 1 FROM search_seen_keys s WHERE s.doc_key = search_documents.doc_key
            )
            """.trimIndent(),
        )
    }

    private fun replaceNames(document: CatalogTextSearchDatabaseStore.PreparedCatalogTextSearchDocument) {
        deleteFtsNames.reset()
        deleteFtsNames.clearBindings()
        deleteFtsNames.bindText(1, document.key)
        deleteFtsNames.step()
        deletePreparedTitles.reset()
        deletePreparedTitles.clearBindings()
        deletePreparedTitles.bindText(1, document.key)
        deletePreparedTitles.step()
        deleteIdentifiers.reset()
        deleteIdentifiers.clearBindings()
        deleteIdentifiers.bindText(1, document.key)
        deleteIdentifiers.step()
        insertNamesAndTitles(document)
    }

    private fun insertNamesAndTitles(
        document: CatalogTextSearchDatabaseStore.PreparedCatalogTextSearchDocument,
    ) {
        document.titles.forEachIndexed { titleOrder, title ->
            insertPreparedTitle.reset()
            insertPreparedTitle.clearBindings()
            insertPreparedTitle.bindText(1, document.key)
            insertPreparedTitle.bindInt(2, titleOrder)
            insertPreparedTitle.bindText(3, title)
            insertPreparedTitle.step()
            CatalogTextSearchDatabaseStore.insertNameVariants(insertFtsName, title, document.key)
        }
        document.identifiers.forEach { identity ->
            insertIdentifier.reset()
            insertIdentifier.clearBindings()
            insertIdentifier.bindText(1, identity.kind)
            insertIdentifier.bindText(2, identity.normalizedValue)
            insertIdentifier.bindText(3, document.key)
            insertIdentifier.step()
        }
    }

    private fun upsertGroup(document: CatalogTextSearchDatabaseStore.PreparedCatalogTextSearchDocument) {
        upsertDocumentGroup.reset()
        upsertDocumentGroup.clearBindings()
        upsertDocumentGroup.bindText(1, document.key)
        upsertDocumentGroup.bindText(2, document.agmGroupId)
        upsertDocumentGroup.step()
    }

    override fun close() {
        listOf(
            selectExisting,
            markSeen,
            insertDocument,
            updateDocument,
            updateNameFingerprint,
            deleteFtsNames,
            deletePreparedTitles,
            deleteIdentifiers,
            insertPreparedTitle,
            insertFtsName,
            insertIdentifier,
            upsertDocumentGroup,
        ).forEach(SQLiteStatement::close)
    }

    private data class ExistingDocument(
        val documentOrder: Int,
        val payloadFingerprint: String,
        val nameFingerprint: String,
    )
}

private const val CANCELLATION_CHECK_MASK = 0x7F
private val ARTICLES = setOf("the", "a", "an")
private val NON_ALNUM_SPLIT = Regex("[^\\p{L}\\p{N}]+")
private val HEX_DIGITS = "0123456789abcdef".toCharArray()

private fun normalizedSearchKeys(value: String): Set<String> = buildSet {
    CatalogRepository.normalizeTitle(value).takeIf(String::isNotEmpty)?.let(::add)
    normalizedWithoutLeadingArticle(value).takeIf(String::isNotEmpty)?.let(::add)
}

private fun normalizedWithoutLeadingArticle(value: String): String {
    val tokens = value
        .lowercase()
        .split(NON_ALNUM_SPLIT)
        .filter(String::isNotBlank)
    if (tokens.firstOrNull() !in ARTICLES) return ""
    return tokens.drop(1).joinToString("").filter(Char::isLetterOrDigit)
}

private fun directTokens(value: String): List<String> =
    value.split(NON_ALNUM_SPLIT).filter { it.length >= 2 }

private fun isSingleDamerauEditApart(left: String, right: String): Boolean {
    val a = left.codePoints().toArray()
    val b = right.codePoints().toArray()
    if (kotlin.math.abs(a.size - b.size) > 1) return false
    if (a.size == b.size) {
        val mismatches = a.indices.filter { a[it] != b[it] }
        return when (mismatches.size) {
            1 -> true
            2 -> {
                val first = mismatches[0]
                val second = mismatches[1]
                second == first + 1 && a[first] == b[second] && a[second] == b[first]
            }
            else -> false
        }
    }
    val shorter = if (a.size < b.size) a else b
    val longer = if (a.size < b.size) b else a
    var shortIndex = 0
    var longIndex = 0
    var skipped = false
    while (shortIndex < shorter.size && longIndex < longer.size) {
        if (shorter[shortIndex] == longer[longIndex]) {
            shortIndex++
            longIndex++
        } else {
            if (skipped) return false
            skipped = true
            longIndex++
        }
    }
    return true
}

private fun titleHasTokenPrefix(title: String, queryToken: String): Boolean {
    var offset = 0
    while (offset < title.length) {
        while (offset < title.length) {
            val codePoint = title.codePointAt(offset)
            if (Character.isLetterOrDigit(codePoint)) break
            offset += Character.charCount(codePoint)
        }
        if (offset >= title.length) return false
        val tokenStart = offset
        while (offset < title.length) {
            val codePoint = title.codePointAt(offset)
            if (!Character.isLetterOrDigit(codePoint)) break
            offset += Character.charCount(codePoint)
        }
        if (title.startsWith(queryToken, tokenStart)) return true
    }
    return false
}

private fun looksLikeAcronymQuery(query: String): Boolean {
    if (query.length < 2 || query.length > 10) return false
    if (query.any(Char::isWhitespace)) return false
    if (query.any(Char::isLowerCase)) return false
    return query.any(Char::isLetter)
}

private fun ftsTrigramQuery(value: String): String {
    val codePoints = value.codePoints().toArray()
    return (0..codePoints.size - 3)
        .map { start -> String(codePoints, start, 3) }
        .distinct()
        .joinToString(" AND ") { token ->
            "\"${token.replace("\"", "\"\"")}\""
        }
}

private fun nameVariants(name: String): Set<String> = linkedSetOf(name.lowercase()).apply {
    addAll(normalizedSearchKeys(name))
    CatalogRepository.catalogCleanKey(name)
        .takeIf(String::isNotEmpty)
        ?.let(::add)
    CatalogRepository.numberEquivalentKey(name)
        .takeIf { it.isNotEmpty() && it != name }
        ?.let(::add)
    CatalogRepository.acronym(CatalogRepository.titleWords(name))
        .takeIf { it.length >= 2 }
        ?.let(::add)
}
