package com.example.f95updater

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class CatalogRowsFilter(
    val matchingKeys: Set<String>?,
    val matchingGroupIds: Set<String>? = null,
    val matchingKeyRanks: Map<String, Double>? = null,
    val matchingGroupRanks: Map<String, Double>? = null,
    val tagTokens: List<String>,
    val canonicalTagIds: List<String> = emptyList(),
    /** Each inner list is one OR-group of tag tokens; groups are AND-ed with each other. */
    val tagTokenGroups: List<List<String>> = emptyList(),
    val statusFilter: Int?,
    val engineFilter: Int?,
    val categoryFilter: String?,
    val sourceFilter: String?,
    val platformFilter: String?,
    val minRating: Float,
    val minPopularity: Long = 0L,
    val updatedSinceIso: String? = null,
    val publishedSinceIso: String? = null,
    val installedOnly: Boolean,
    val notInstalledOnly: Boolean,
    val installedCatalogKeys: Set<CatalogInstallKey>,
    val installedCatalogUrls: Set<String>,
    val installedAgmGroupIds: Set<String> = emptySet(),
    val wishlistOnly: Boolean,
    val notWishlistOnly: Boolean,
    val wishlistGroupIds: Set<String>,
    val ignoredOnly: Boolean = false,
    val ignoredGroupIds: Set<String> = emptySet(),
    val sortKey: CatalogSortKey,
    val sortDesc: Boolean,
    /** Rows whose entry_key is in here are excluded (from `-word` free-text exclusion). */
    val excludedKeys: Set<String>? = null,
    /** Tag tokens to exclude (from `-tag:xxx`). */
    val excludedTagTokens: List<String> = emptyList(),
)

internal data class CatalogRowsPage(
    val totalCount: Int,
    val entries: List<SourceCatalogEntry>,
)

/** One cross-source game group: the [agmGroupId] and every member source entry that belongs to it
 *  (all sources, regardless of the active filter, so the detail popup can show a tab per source). */
internal data class CatalogRowsGroup(
    val agmGroupId: String,
    val members: List<SourceCatalogEntry>,
)

internal data class CatalogRowsGroupPage(
    val totalCount: Int,
    val groups: List<CatalogRowsGroup>,
)

/**
 * One source entry supplied to [CatalogRowsDatabaseStore.synchronizeStreamingFromSourceEntries].
 * Aliases are kept with their entry so callers need not materialize a catalog-wide alias map.
 */
internal data class CatalogRowsStreamEntry(
    val entry: SourceCatalogEntry,
    val translatedTitleAliases: List<String> = emptyList(),
)

/** The production catalog uses FILE so large filter sets do not compete with the app heap. */
internal enum class CatalogRowsTempStoreMode(val pragmaValue: String) {
    MEMORY("MEMORY"),
    FILE("FILE"),
}

internal object CatalogRowsDatabaseStore {
    private const val SCHEMA_VERSION = "3"
    private const val META_SCHEMA = "schema"
    private const val META_SIGNATURE = "signature"
    private const val VALUE_SEPARATOR = "\u001F"
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun isValid(file: File, expectedSignature: String): Boolean {
        if (!file.isFile) return false
        return try {
            BundledSQLiteDriver().open(
                file.absolutePath,
                SQLITE_OPEN_READONLY or SQLITE_OPEN_FULLMUTEX,
            ).use { connection ->
                isRowSchemaCompatible(connection) &&
                    CatalogTextSearchDatabaseStore.isCompatible(connection) &&
                    readMeta(connection, META_SIGNATURE) == expectedSignature &&
                    queryInt(connection, "SELECT COUNT(*) FROM catalog_rows") > 0
            }
        } catch (error: Exception) {
            AppLog.w("CatalogRows", "Catalog rows database is unreadable", error)
            deleteDatabaseFiles(file)
            false
        }
    }

    fun build(
        file: File,
        signature: String,
        rows: List<CatalogSearchEntry>,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        synchronize(file, signature, rows, onProgress = onProgress)
    }

    fun buildFromSourceEntries(
        file: File,
        signature: String,
        entries: List<SourceCatalogEntry>,
        labels: CatalogLabelsV2?,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        synchronizeFromSourceEntries(file, signature, entries, labels, onProgress = onProgress)
    }

    /**
     * Synchronizes a compatible database in place. A missing or incompatible database is built
     * in a sibling temporary file and atomically installed only after its transaction succeeds.
     */
    fun synchronize(
        file: File,
        signature: String,
        rows: List<CatalogSearchEntry>,
        translatedTitleAliases: Map<String, List<String>> = emptyMap(),
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {},
        tempStore: CatalogRowsTempStoreMode = CatalogRowsTempStoreMode.FILE,
    ) = synchronizeGenerated(
        file = file,
        signature = signature,
        expectedCount = rows.size,
        producer = { emit ->
            rows.forEach { row ->
                val entry = row.entry
                emit(row, translatedTitleAliases[catalogTextSearchKey(entry.source, entry.sourceId)].orEmpty())
            }
        },
        onProgress = onProgress,
        checkCancelled = checkCancelled,
        tempStore = tempStore,
    )

    /**
     * Streams a complete source catalog into the rows database. [producer] must call its
     * receiver exactly [expectedCount] times; doing so keeps count/progress validation and
     * deletion semantics explicit without retaining the input catalog in memory.
     */
    fun synchronizeStreamingFromSourceEntries(
        file: File,
        signature: String,
        expectedCount: Int? = null,
        labels: CatalogLabelsV2?,
        producer: ((CatalogRowsStreamEntry) -> Unit) -> Unit,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {},
        tempStore: CatalogRowsTempStoreMode = CatalogRowsTempStoreMode.FILE,
    ) = synchronizeGenerated(
        file = file,
        signature = signature,
        expectedCount = expectedCount,
        producer = { emit ->
            producer { streamed ->
                emit(
                    CatalogSearchEntry.from(streamed.entry, labels),
                    streamed.translatedTitleAliases,
                )
            }
        },
        onProgress = onProgress,
        checkCancelled = checkCancelled,
        tempStore = tempStore,
    )

    private fun synchronizeGenerated(
        file: File,
        signature: String,
        expectedCount: Int?,
        producer: ((CatalogSearchEntry, List<String>) -> Unit) -> Unit,
        onProgress: (completed: Int, total: Int) -> Unit,
        checkCancelled: () -> Unit,
        tempStore: CatalogRowsTempStoreMode,
    ) {
        try {
            synchronizeGeneratedOnce(
                file, signature, expectedCount, producer, onProgress, checkCancelled, tempStore,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (first: Throwable) {
            // Only a storage-level I/O failure warrants nuking artifacts and rebuilding: a
            // producer/logic failure must preserve the previously committed catalog (the
            // rollback contract). A transient disk I/O error, by contrast, can corrupt the
            // destination or leave the initial rebuild wedged, so purge every artifact and
            // rebuild once from a guaranteed-clean slate before giving up.
            if (!isDiskIoError(first)) throw first
            AppLog.w(
                "CatalogRows",
                "Catalog database build hit a disk I/O error (${first.message}); " +
                    "purging artifacts and retrying once",
            )
            purgeAllDatabaseFiles(file)
            checkCancelled()
            try {
                synchronizeGeneratedOnce(
                    file, signature, expectedCount, producer, onProgress, checkCancelled, tempStore,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (second: Throwable) {
                second.addSuppressed(first)
                throw second
            }
        }
    }

    /** True when [error] (or any cause) is a SQLite disk I/O error (SQLITE_IOERR, code 10). */
    private fun isDiskIoError(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message?.lowercase()
            if (message != null &&
                (message.contains("disk i/o error") ||
                    message.contains("code 10") ||
                    message.contains("code: 10"))
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /** Deletes the destination database plus its temp and backup siblings and all journals. */
    private fun purgeAllDatabaseFiles(file: File) {
        deleteDatabaseFiles(file)
        file.parentFile?.let { parent ->
            deleteDatabaseFiles(File(parent, "${file.name}.tmp"))
            deleteDatabaseFiles(File(parent, "${file.name}.bak"))
        }
    }

    private fun synchronizeGeneratedOnce(
        file: File,
        signature: String,
        expectedCount: Int?,
        producer: ((CatalogSearchEntry, List<String>) -> Unit) -> Unit,
        onProgress: (completed: Int, total: Int) -> Unit,
        checkCancelled: () -> Unit,
        tempStore: CatalogRowsTempStoreMode,
    ) {
        require(expectedCount == null || expectedCount > 0) {
            "Expected catalog row count must be positive when provided"
        }
        file.parentFile?.mkdirs()
        // If the existing database already matches this signature (and is schema-compatible and
        // non-empty), it is up to date — skip the rebuild. Callers normally gate on isValid()
        // already, but keeping the guard here preserves the "no-op for matching signature"
        // contract without draining the fast path below.
        if (file.isFile && isValid(file, signature)) {
            return
        }
        // Otherwise (re)build via the fast fresh path into a throwaway .tmp, then atomically
        // replace. The previous in-place "Updated" path re-probed and re-fingerprinted all
        // ~74k rows (tens of seconds); a full fresh build is far faster and produces the same
        // complete database from the same producer, so there is no reason to keep the slow path.
        val temporary = File(file.parentFile, "${file.name}.tmp")
        deleteDatabaseFiles(temporary)
        try {
            BundledSQLiteDriver().open(
                temporary.absolutePath,
                SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE or SQLITE_OPEN_FULLMUTEX,
            ).use { connection ->
                configureForBuild(connection)
                createSchema(connection)
                updateDatabase(
                    connection,
                    "Built",
                    signature,
                    expectedCount,
                    producer,
                    onProgress,
                    checkCancelled,
                    freshBuild = true,
                )
                createIndexes(connection)
            }
            checkCancelled()
            replaceDatabase(temporary, file)
        } catch (error: Throwable) {
            deleteDatabaseFiles(temporary)
            throw error
        }
    }

    fun synchronizeFromSourceEntries(
        file: File,
        signature: String,
        entries: List<SourceCatalogEntry>,
        labels: CatalogLabelsV2?,
        translatedTitleAliases: Map<String, List<String>> = emptyMap(),
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {},
        tempStore: CatalogRowsTempStoreMode = CatalogRowsTempStoreMode.FILE,
    ) = synchronizeGenerated(
        file = file,
        signature = signature,
        expectedCount = entries.size,
        producer = { emit ->
            entries.forEach { entry ->
                emit(
                    CatalogSearchEntry.from(entry, labels),
                    translatedTitleAliases[catalogTextSearchKey(entry.source, entry.sourceId)].orEmpty(),
                )
            }
        },
        onProgress = onProgress,
        checkCancelled = checkCancelled,
        tempStore = tempStore,
    )

    fun totalCount(file: File): Int =
        openReadOnly(file).use { connection ->
            queryInt(connection, "SELECT COUNT(*) FROM catalog_rows")
        }

    fun tagLabels(file: File): List<String> =
        openReadOnly(file).use { connection ->
            buildList {
                connection.prepare(
                    "SELECT label FROM catalog_tag_labels ORDER BY label COLLATE NOCASE",
                ).use { statement ->
                    while (statement.step()) add(statement.getText(0))
                }
            }
        }

    fun entriesByCatalogKeys(file: File, keys: Set<String>): List<SourceCatalogEntry> =
        openReadWrite(file).use { connection ->
            queryEntriesByValues(connection, "target_catalog_keys", "entry_key", keys)
        }

    fun entriesByKeys(file: File, keys: Set<String>): List<SourceCatalogEntry> =
        entriesByCatalogKeys(file, keys)

    fun catalogGamesByF95ThreadIds(file: File, threadIds: Set<Int>): Map<Int, CatalogGame> =
        openReadWrite(file).use { connection ->
            if (threadIds.isEmpty()) return@use emptyMap()
            connection.execSQL(
                "CREATE TEMP TABLE target_f95_thread_ids(thread_id INTEGER PRIMARY KEY) WITHOUT ROWID",
            )
            connection.prepare(
                "INSERT OR IGNORE INTO target_f95_thread_ids(thread_id) VALUES (?)",
            ).use { statement ->
                threadIds.forEach { threadId ->
                    statement.reset()
                    statement.clearBindings()
                    statement.bindInt(1, threadId)
                    statement.step()
                }
            }
            connection.prepare(
                """
                SELECT f95_thread_id, entry_json FROM catalog_rows
                WHERE source = ? AND f95_thread_id IN (
                    SELECT thread_id FROM target_f95_thread_ids
                )
                """.trimIndent(),
            ).use { statement ->
                statement.bindText(1, SOURCE_F95ZONE)
                buildMap {
                    while (statement.step()) {
                        put(
                            statement.getLong(0).toInt(),
                            sourceEntryToCatalogGame(json.decodeFromString(statement.getText(1))),
                        )
                    }
                }
            }
        }

    fun groupForNavigation(
        file: File,
        identity: CatalogNavigationIdentity,
    ): CatalogRowsGroup? = openReadOnly(file).use { connection ->
        val groupId = identity.agmGroupId?.let { candidate ->
            connection.prepare(
                "SELECT agm_group_id FROM catalog_rows WHERE agm_group_id = ? LIMIT 1",
            ).use { statement ->
                statement.bindText(1, candidate)
                if (statement.step()) statement.getText(0) else null
            }
        } ?: if (identity.source != null && identity.sourceId != null) {
            connection.prepare(
                "SELECT agm_group_id FROM catalog_rows WHERE source = ? AND source_id = ? LIMIT 1",
            ).use { statement ->
                statement.bindText(1, identity.source)
                statement.bindText(2, identity.sourceId)
                if (statement.step()) statement.getText(0) else null
            }
        } else {
            null
        } ?: identity.canonicalUrl?.let { url ->
            connection.prepare(
                "SELECT agm_group_id FROM catalog_rows WHERE canonical_url = ? LIMIT 1",
            ).use { statement ->
                statement.bindText(1, url)
                if (statement.step()) statement.getText(0) else null
            }
        } ?: return@use null
        connection.prepare(
            "SELECT entry_json FROM catalog_rows WHERE agm_group_id = ? ORDER BY source, source_id",
        ).use { statement ->
            statement.bindText(1, groupId)
            CatalogRowsGroup(
                agmGroupId = groupId,
                members = buildList {
                    while (statement.step()) {
                        add(json.decodeFromString<SourceCatalogEntry>(statement.getText(0)))
                    }
                },
            )
        }
    }

    fun catalogGameByF95ThreadId(file: File, threadId: Int): CatalogGame? =
        catalogGamesByF95ThreadIds(file, setOf(threadId))[threadId]

    fun translationEntries(
        file: File,
        offset: Int,
        limit: Int,
    ): List<SourceCatalogEntry> {
        require(offset >= 0) { "Translation offset must not be negative" }
        require(limit > 0) { "Translation page size must be positive" }
        return openReadOnly(file).use { connection ->
            connection.prepare(
                """
                SELECT entry_json FROM catalog_rows
                ORDER BY source, source_id
                LIMIT ? OFFSET ?
                """.trimIndent(),
            ).use { statement ->
                statement.bindInt(1, limit)
                statement.bindInt(2, offset)
                buildList(limit) {
                    while (statement.step()) {
                        add(json.decodeFromString(statement.getText(0)))
                    }
                }
            }
        }
    }

    fun openQuery(file: File, filter: CatalogRowsFilter): CatalogRowsQuery =
        CatalogRowsQuery(
            connection = openReadWrite(file),
            filter = filter,
            json = json,
            valueSeparator = VALUE_SEPARATOR,
        )

    /**
     * Single-pass scan of `catalog_rows.title_lower` for the whole-word/regex search modes that FTS
     * cannot express. Returns matching entry_keys for an optional [include] predicate and an optional
     * [exclude] predicate. A null predicate yields a null set (no constraint from that axis). Titles
     * are already lowercased, so predicates should be case-insensitive.
     */
    fun scanTitleKeys(
        file: File,
        include: ((String) -> Boolean)?,
        exclude: ((String) -> Boolean)?,
        checkCancelled: () -> Unit = {},
    ): Pair<Set<String>?, Set<String>?> {
        if (include == null && exclude == null) return null to null
        val includeKeys = if (include != null) linkedSetOf<String>() else null
        val excludeKeys = if (exclude != null) linkedSetOf<String>() else null
        openReadOnly(file).use { connection ->
            connection.prepare("SELECT entry_key, title_lower FROM catalog_rows").use { statement ->
                var scanned = 0
                while (statement.step()) {
                    if (++scanned % 2048 == 0) checkCancelled()
                    val title = statement.getText(1)
                    if (include != null && include(title)) includeKeys!!.add(statement.getText(0))
                    if (exclude != null && exclude(title)) excludeKeys!!.add(statement.getText(0))
                }
            }
        }
        return includeKeys to excludeKeys
    }

    private fun isSchemaCompatible(file: File): Boolean = try {
        openReadOnly(file).use { connection ->
            isRowSchemaCompatible(connection) && CatalogTextSearchDatabaseStore.isCompatible(connection)
        }
    } catch (_: Exception) {
        false
    }

    private fun openReadOnly(file: File): SQLiteConnection =
        BundledSQLiteDriver().open(
            file.absolutePath,
            SQLITE_OPEN_READONLY or SQLITE_OPEN_FULLMUTEX,
        )

    private fun openReadWrite(file: File): SQLiteConnection =
        BundledSQLiteDriver().open(
            file.absolutePath,
            SQLITE_OPEN_READWRITE or SQLITE_OPEN_FULLMUTEX,
        ).also { configure(it, CatalogRowsTempStoreMode.FILE) }

    private fun configure(connection: SQLiteConnection, tempStore: CatalogRowsTempStoreMode) {
        CatalogTextSearchDatabaseStore.configure(connection)
        connection.execSQL("PRAGMA temp_store=${tempStore.pragmaValue}")
        connection.execSQL("PRAGMA cache_size=-2000")
    }

    /**
     * Aggressive pragmas for building the throwaway catalog_rows.db.tmp. A large page cache keeps
     * the growing indexes/FTS resident (a 2 MB cache thrashed to disk, dominating build time), and
     * because the temp database is deleted on any failure we can safely drop journaling and fsync.
     */
    private fun configureForBuild(connection: SQLiteConnection) {
        CatalogTextSearchDatabaseStore.configure(connection)
        connection.execSQL("PRAGMA temp_store=MEMORY")
        connection.execSQL("PRAGMA cache_size=-65536")
        connection.execSQL("PRAGMA journal_mode=MEMORY")
        connection.execSQL("PRAGMA synchronous=OFF")
    }

    private fun createSchema(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE catalog_rows_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        connection.execSQL(
            """
            CREATE TABLE catalog_rows(
                row_id INTEGER PRIMARY KEY,
                entry_key TEXT NOT NULL UNIQUE,
                source TEXT NOT NULL,
                source_id TEXT NOT NULL,
                f95_thread_id INTEGER,
                canonical_url TEXT NOT NULL,
                entry_json TEXT NOT NULL,
                metadata_fingerprint TEXT NOT NULL,
                title_lower TEXT NOT NULL,
                tag_tokens TEXT NOT NULL,
                tag_labels_json TEXT NOT NULL,
                numeric_tags TEXT NOT NULL,
                categories TEXT NOT NULL,
                platforms TEXT NOT NULL,
                sort_rating REAL NOT NULL,
                sort_popularity REAL NOT NULL,
                sort_updated TEXT NOT NULL,
                sort_newest TEXT NOT NULL,
                agm_group_id TEXT NOT NULL,
                canonical_tags TEXT NOT NULL
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE TABLE catalog_tag_labels(token TEXT PRIMARY KEY, label TEXT NOT NULL)",
        )
        CatalogTextSearchDatabaseStore.createSchema(connection)
        writeMeta(connection, META_SCHEMA, SCHEMA_VERSION)
    }

    private fun createIndexes(connection: SQLiteConnection) {
        connection.execSQL("CREATE INDEX idx_catalog_rows_source ON catalog_rows(source)")
        connection.execSQL("CREATE INDEX idx_catalog_rows_source_id ON catalog_rows(source, source_id)")
        connection.execSQL(
            "CREATE INDEX idx_catalog_rows_f95_thread ON catalog_rows(f95_thread_id) " +
                "WHERE f95_thread_id IS NOT NULL",
        )
        connection.execSQL("CREATE INDEX idx_catalog_rows_url ON catalog_rows(canonical_url)")
        connection.execSQL("CREATE INDEX idx_catalog_rows_group ON catalog_rows(agm_group_id)")
        connection.execSQL("CREATE INDEX idx_catalog_rows_title ON catalog_rows(title_lower, row_id)")
        connection.execSQL(
            "CREATE INDEX idx_catalog_rows_rating " +
                "ON catalog_rows(sort_rating, sort_popularity, row_id)",
        )
        connection.execSQL(
            "CREATE INDEX idx_catalog_rows_updated ON catalog_rows(sort_updated, row_id)",
        )
        connection.execSQL(
            "CREATE INDEX idx_catalog_rows_newest ON catalog_rows(sort_newest, row_id)",
        )
        connection.execSQL(
            "CREATE INDEX idx_catalog_rows_popularity ON catalog_rows(sort_popularity, row_id)",
        )
    }

    private fun updateDatabase(
        connection: SQLiteConnection,
        operation: String,
        signature: String,
        expectedCount: Int?,
        producer: ((CatalogSearchEntry, List<String>) -> Unit) -> Unit,
        onProgress: (completed: Int, total: Int) -> Unit,
        checkCancelled: () -> Unit,
        freshBuild: Boolean,
    ) {
        val startedAt = System.currentTimeMillis()
        var inserted = 0
        var updated = 0
        var unchanged = 0
        var processed = 0
        val labelsByToken = LinkedHashMap<String, String>()
        connection.execSQL("BEGIN IMMEDIATE")
        try {
            connection.execSQL(
                "CREATE TEMP TABLE IF NOT EXISTS catalog_rows_seen_keys(" +
                    "entry_key TEXT PRIMARY KEY) WITHOUT ROWID",
            )
            connection.execSQL("DELETE FROM catalog_rows_seen_keys")
            CatalogRowsWriter(connection, freshBuild).use { rowsWriter ->
                CatalogTextSearchDatabaseStore.beginUpdate(connection, freshBuild).use { searchWriter ->
                    producer { row, aliases ->
                        checkCancelled()
                        check(expectedCount == null || processed < expectedCount) {
                            "Catalog stream emitted more than expected ($expectedCount) entries"
                        }
                        val rowId = rowsWriter.upsert(row)
                        when (rowsWriter.lastChange) {
                            RowChange.INSERTED -> inserted++
                            RowChange.UPDATED -> updated++
                            RowChange.UNCHANGED -> unchanged++
                        }
                        val entry = row.entry
                        val key = catalogTextSearchKey(entry.source, entry.sourceId)
                        searchWriter.upsert(
                            CatalogTextSearchDocument(
                                key = key,
                                displayTitle = entry.title,
                                titles = listOf(entry.title) + aliases,
                                sourceEntry = entry,
                            ),
                            rowId,
                        )
                        row.tagLabels.forEach { label ->
                            val trimmed = label.trim()
                            val token = catalogTagFilterToken(trimmed)
                            if (trimmed.isNotEmpty() && token.isNotEmpty()) {
                                labelsByToken.putIfAbsent(token, trimmed)
                            }
                        }
                        processed++
                        if (processed % PROGRESS_INTERVAL == 0 || processed == expectedCount) {
                            onProgress(processed, expectedCount ?: processed)
                        }
                    }
                    checkCancelled()
                    check(processed > 0) { "Cannot build an empty catalog rows database" }
                    check(expectedCount == null || processed == expectedCount) {
                        "Catalog stream emitted $processed entries; expected $expectedCount"
                    }
                    if (expectedCount == null && processed % PROGRESS_INTERVAL != 0) {
                        onProgress(processed, processed)
                    }
                    val removed = if (freshBuild) 0 else rowsWriter.deleteUnseen()
                    if (!freshBuild) searchWriter.deleteUnseen()
                    replaceTagLabels(connection, labelsByToken)
                    writeMeta(connection, META_SIGNATURE, signature)
                    connection.execSQL("COMMIT")
                    AppLog.i(
                        "CatalogRows",
                        "$operation rows=$processed inserted=$inserted updated=$updated " +
                            "unchanged=$unchanged removed=$removed in ${System.currentTimeMillis() - startedAt}ms",
                    )
                }
            }
        } catch (error: Throwable) {
            try {
                connection.execSQL("ROLLBACK")
            } catch (rollbackError: Exception) {
                error.addSuppressed(rollbackError)
            }
            throw error
        }
    }

    private fun replaceTagLabels(
        connection: SQLiteConnection,
        labelsByToken: Map<String, String>,
    ) {
        connection.execSQL("DELETE FROM catalog_tag_labels")
        connection.prepare(
            "INSERT INTO catalog_tag_labels(token, label) VALUES (?, ?)",
        ).use { statement ->
            labelsByToken.entries
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.value })
                .forEach { (token, label) ->
                    statement.reset()
                    statement.clearBindings()
                    statement.bindText(1, token)
                    statement.bindText(2, label)
                    statement.step()
                }
        }
    }

    private fun queryEntriesByValues(
        connection: SQLiteConnection,
        table: String,
        column: String,
        values: Set<String>,
    ): List<SourceCatalogEntry> {
        if (values.isEmpty()) return emptyList()
        connection.execSQL("CREATE TEMP TABLE $table($column TEXT PRIMARY KEY) WITHOUT ROWID")
        connection.prepare("INSERT OR IGNORE INTO $table($column) VALUES (?)").use { statement ->
            values.forEach { value ->
                statement.reset()
                statement.clearBindings()
                statement.bindText(1, value)
                statement.step()
            }
        }
        return connection.prepare(
            "SELECT entry_json FROM catalog_rows WHERE entry_key IN (SELECT $column FROM $table)",
        ).use { statement ->
            buildList {
                while (statement.step()) add(json.decodeFromString(statement.getText(0)))
            }
        }
    }

    private fun isRowSchemaCompatible(connection: SQLiteConnection): Boolean {
        if (readMeta(connection, META_SCHEMA) != SCHEMA_VERSION) return false
        return connection.prepare("PRAGMA table_info(catalog_rows)").use { statement ->
            val columns = buildSet {
                while (statement.step()) add(statement.getText(1))
            }
            setOf("source_id", "f95_thread_id", "metadata_fingerprint", "tag_labels_json")
                .all(columns::contains)
        }
    }

    private fun packValues(values: Collection<String>): String =
        if (values.isEmpty()) "" else VALUE_SEPARATOR + values.joinToString(VALUE_SEPARATOR) + VALUE_SEPARATOR

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    private fun toHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = HEX_DIGITS[v ushr 4]
            chars[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
        }
        return String(chars)
    }

    private fun rowMetadataFingerprint(row: CatalogSearchEntry): String =
        MessageDigest.getInstance("SHA-256").run {
            listOf(
                json.encodeToString(row.entry),
                row.titleLower,
                packValues(row.tagTokens.sorted()),
                json.encodeToString(row.tagLabels),
                packValues(row.numericTagIds.sorted().map(Int::toString)),
            ).forEach {
                update(it.toByteArray(Charsets.UTF_8))
                update(0)
            }
            toHex(digest())
        }

    private fun readMeta(connection: SQLiteConnection, key: String): String? =
        connection.prepare(
            "SELECT value FROM catalog_rows_meta WHERE key = ?",
        ).use { statement ->
            statement.bindText(1, key)
            if (statement.step()) statement.getText(0) else null
        }

    private fun writeMeta(connection: SQLiteConnection, key: String, value: String) {
        connection.prepare(
            "INSERT OR REPLACE INTO catalog_rows_meta(key, value) VALUES (?, ?)",
        ).use { statement ->
            statement.bindText(1, key)
            statement.bindText(2, value)
            statement.step()
        }
    }

    private fun queryInt(connection: SQLiteConnection, sql: String): Int =
        connection.prepare(sql).use { statement ->
            check(statement.step()) { "Catalog rows query returned no result" }
            statement.getLong(0).toInt()
        }

    private fun replaceDatabase(temporary: File, target: File) {
        val backup = File(target.parentFile, "${target.name}.bak")
        deleteDatabaseFiles(backup)
        if (target.exists() && !target.renameTo(backup)) {
            deleteDatabaseFiles(temporary)
            error("Could not replace catalog rows database")
        }
        if (!temporary.renameTo(target)) {
            backup.renameTo(target)
            deleteDatabaseFiles(temporary)
            error("Could not finalize catalog rows database")
        }
        deleteDatabaseFiles(backup)
    }

    internal fun deleteDatabaseFiles(file: File) {
        listOf(file, File("${file.path}-journal"), File("${file.path}-wal"), File("${file.path}-shm"))
            .forEach { candidate ->
                if (candidate.exists() && !candidate.delete()) {
                    AppLog.w("CatalogRows", "Could not delete ${candidate.name}")
                }
            }
    }

    private const val PROGRESS_INTERVAL = 2_000
    private enum class RowChange { INSERTED, UPDATED, UNCHANGED }

    private class CatalogRowsWriter(
        private val connection: SQLiteConnection,
        private val freshBuild: Boolean,
    ) : AutoCloseable {
        var lastChange = RowChange.UNCHANGED
            private set
        private val selectExisting = connection.prepare(
            "SELECT row_id, metadata_fingerprint FROM catalog_rows WHERE entry_key = ?",
        )
        private val markSeen = connection.prepare(
            "INSERT OR REPLACE INTO catalog_rows_seen_keys(entry_key) VALUES (?)",
        )
        private val insert = connection.prepare(
            """
            INSERT INTO catalog_rows(
                entry_key, source, source_id, f95_thread_id, canonical_url, entry_json,
                metadata_fingerprint, title_lower, tag_tokens, tag_labels_json, numeric_tags,
                categories, platforms, sort_rating, sort_popularity, sort_updated, sort_newest,
                agm_group_id, canonical_tags
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        )
        private val selectRowId = connection.prepare(
            "SELECT row_id FROM catalog_rows WHERE entry_key = ?",
        )
        private val lastRowId = connection.prepare("SELECT last_insert_rowid()")
        private val update = connection.prepare(
            """
            UPDATE catalog_rows SET
                source = ?, source_id = ?, f95_thread_id = ?, canonical_url = ?, entry_json = ?,
                metadata_fingerprint = ?, title_lower = ?, tag_tokens = ?, tag_labels_json = ?,
                numeric_tags = ?, categories = ?, platforms = ?, sort_rating = ?,
                sort_popularity = ?, sort_updated = ?, sort_newest = ?,
                agm_group_id = ?, canonical_tags = ?
            WHERE entry_key = ?
            """.trimIndent(),
        )

        fun upsert(row: CatalogSearchEntry): Int {
            val entry = row.entry
            val key = catalogTextSearchKey(entry.source, entry.sourceId)
            val fingerprint = rowMetadataFingerprint(row)
            if (freshBuild) {
                // Empty target: every row is new, so skip the existing-lookup, seen-key
                // bookkeeping, and post-insert row_id query (use last_insert_rowid instead).
                bindRow(insert, row, fingerprint, includeEntryKey = true)
                insert.step()
                lastChange = RowChange.INSERTED
                lastRowId.reset()
                check(lastRowId.step())
                return lastRowId.getLong(0).toInt()
            }
            selectExisting.reset()
            selectExisting.clearBindings()
            selectExisting.bindText(1, key)
            val existingRowId = if (selectExisting.step()) selectExisting.getLong(0).toInt() else null
            val existingFingerprint = if (existingRowId != null) selectExisting.getText(1) else null
            markSeen.reset()
            markSeen.clearBindings()
            markSeen.bindText(1, key)
            markSeen.step()
            if (existingRowId == null) {
                bindRow(insert, row, fingerprint, includeEntryKey = true)
                insert.step()
                lastChange = RowChange.INSERTED
                selectRowId.reset()
                selectRowId.clearBindings()
                selectRowId.bindText(1, key)
                check(selectRowId.step())
                return selectRowId.getLong(0).toInt()
            }
            if (existingFingerprint != fingerprint) {
                bindRow(update, row, fingerprint, includeEntryKey = false)
                update.bindText(19, key)
                update.step()
                lastChange = RowChange.UPDATED
            } else {
                lastChange = RowChange.UNCHANGED
            }
            return existingRowId
        }

        fun deleteUnseen(): Int {
            val count = queryInt(
                connection,
                """
                SELECT COUNT(*) FROM catalog_rows
                WHERE NOT EXISTS (
                    SELECT 1 FROM catalog_rows_seen_keys s WHERE s.entry_key = catalog_rows.entry_key
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                DELETE FROM catalog_rows
                WHERE NOT EXISTS (
                    SELECT 1 FROM catalog_rows_seen_keys s WHERE s.entry_key = catalog_rows.entry_key
                )
                """.trimIndent(),
            )
            return count
        }

        private fun bindRow(
            statement: SQLiteStatement,
            row: CatalogSearchEntry,
            fingerprint: String,
            includeEntryKey: Boolean,
        ) {
            val entry = row.entry
            statement.reset()
            statement.clearBindings()
            val key = catalogTextSearchKey(entry.source, entry.sourceId)
            var binding = 1
            if (includeEntryKey) statement.bindText(binding++, key)
            statement.bindText(binding++, entry.source)
            statement.bindText(binding++, entry.sourceId)
            if (entry.source == SOURCE_F95ZONE) {
                entry.sourceId.toIntOrNull()?.let { statement.bindInt(binding, it) }
            }
            binding++
            statement.bindText(binding++, entry.canonicalUrl)
            statement.bindText(binding++, json.encodeToString(entry))
            statement.bindText(binding++, fingerprint)
            statement.bindText(binding++, row.titleLower)
            statement.bindText(binding++, packValues(row.tagTokens))
            statement.bindText(binding++, json.encodeToString(row.tagLabels))
            statement.bindText(binding++, packValues(row.numericTagIds.map(Int::toString)))
            statement.bindText(binding++, packValues(entry.tags.map(String::lowercase)))
            statement.bindText(binding++, packValues(entry.platforms.map { it.lowercase() }))
            statement.bindDouble(binding++, entry.rating ?: 0.0)
            statement.bindDouble(binding++, entry.popularity ?: 0.0)
            statement.bindText(binding++, entry.modifiedAt ?: entry.publishedAt ?: "")
            statement.bindText(binding++, entry.publishedAt ?: entry.modifiedAt ?: "")
            statement.bindText(binding++, entry.agmGroupId?.takeIf { it.isNotBlank() } ?: key)
            statement.bindText(binding, packValues(entry.canonicalTags.map(String::lowercase)))
        }

        override fun close() {
            listOf(selectExisting, markSeen, insert, selectRowId, lastRowId, update).forEach(SQLiteStatement::close)
        }
    }
}

internal class CatalogRowsQuery(
    private val connection: SQLiteConnection,
    private val filter: CatalogRowsFilter,
    private val json: Json,
    private val valueSeparator: String,
) : AutoCloseable {
    private val whereParams = mutableListOf<Any>()
    private val whereSql: String
    private val orderBySql: String
    private val groupOrderBySql: String
    private var cachedCount: Int? = null
    private var cachedGroupCount: Int? = null

    init {
        populateRankedTempTable(
            "temp_search_keys",
            "entry_key",
            filter.matchingKeys,
            filter.matchingKeyRanks,
        )
        populateRankedTempTable(
            "temp_search_groups",
            "agm_group_id",
            filter.matchingGroupIds,
            filter.matchingGroupRanks,
        )
        populateTempTable("temp_exclude_keys", "entry_key", filter.excludedKeys)
        populateTempTable(
            "temp_installed_keys",
            "entry_key",
            filter.installedCatalogKeys.mapTo(linkedSetOf()) {
                catalogTextSearchKey(it.source, it.sourceId)
            },
        )
        populateTempTable("temp_installed_urls", "canonical_url", filter.installedCatalogUrls)
        populateTempTable("temp_installed_groups", "agm_group_id", filter.installedAgmGroupIds)
        populateTempTable("temp_wishlist_groups", "agm_group_id", filter.wishlistGroupIds)
        populateTempTable("temp_ignored_groups", "agm_group_id", filter.ignoredGroupIds)
        whereSql = buildWhereSql()
        orderBySql = buildOrderBySql()
        groupOrderBySql = buildGroupOrderBySql()
    }

    @Synchronized
    fun load(offset: Int, limit: Int): CatalogRowsPage {
        require(offset >= 0) { "Offset must not be negative" }
        require(limit > 0) { "Limit must be positive" }
        val total = queryCount()
        val entries = buildList(limit) {
            connection.prepare(
                "SELECT entry_json FROM catalog_rows $whereSql $orderBySql LIMIT ? OFFSET ?",
            ).use { statement ->
                bindAll(statement, whereParams)
                statement.bindInt(whereParams.size + 1, limit)
                statement.bindInt(whereParams.size + 2, offset)
                while (statement.step()) {
                    add(json.decodeFromString<SourceCatalogEntry>(statement.getText(0)))
                }
            }
        }
        return CatalogRowsPage(total, entries)
    }

    @Synchronized
    fun queryCount(): Int {
        cachedCount?.let { return it }
        return connection.prepare(
            "SELECT COUNT(*) FROM catalog_rows $whereSql",
        ).use { statement ->
            bindAll(statement, whereParams)
            check(statement.step()) { "Catalog row count returned no result" }
            statement.getLong(0).toInt().also { cachedCount = it }
        }
    }

    /** Number of distinct cross-source groups matching the current filter. */
    @Synchronized
    fun queryGroupCount(): Int {
        cachedGroupCount?.let { return it }
        return connection.prepare(
            "SELECT COUNT(DISTINCT agm_group_id) FROM catalog_rows $whereSql",
        ).use { statement ->
            bindAll(statement, whereParams)
            check(statement.step()) { "Catalog group count returned no result" }
            statement.getLong(0).toInt().also { cachedGroupCount = it }
        }
    }

    /**
     * Grouped page: one [CatalogRowsGroup] per distinct `agm_group_id`, ordered by the aggregate of
     * the active sort key across the group's members. A group matches when ANY of its members match
     * the filter, but each returned group carries EVERY member source (a second, unfiltered fetch by
     * group id) so the row can show all source badges and the detail popup a tab per source.
     */
    @Synchronized
    fun loadGroups(offset: Int, limit: Int): CatalogRowsGroupPage {
        require(offset >= 0) { "Offset must not be negative" }
        require(limit > 0) { "Limit must be positive" }
        val total = queryGroupCount()
        val orderedIds = buildList(limit) {
            connection.prepare(
                "SELECT agm_group_id FROM catalog_rows $whereSql " +
                    "GROUP BY agm_group_id $groupOrderBySql LIMIT ? OFFSET ?",
            ).use { statement ->
                bindAll(statement, whereParams)
                statement.bindInt(whereParams.size + 1, limit)
                statement.bindInt(whereParams.size + 2, offset)
                while (statement.step()) add(statement.getText(0))
            }
        }
        if (orderedIds.isEmpty()) return CatalogRowsGroupPage(total, emptyList())
        val membersByGroup = LinkedHashMap<String, MutableList<SourceCatalogEntry>>()
        orderedIds.forEach { membersByGroup[it] = mutableListOf() }
        val placeholders = orderedIds.joinToString(",") { "?" }
        connection.prepare(
            "SELECT agm_group_id, entry_json FROM catalog_rows WHERE agm_group_id IN ($placeholders)",
        ).use { statement ->
            orderedIds.forEachIndexed { index, id -> statement.bindText(index + 1, id) }
            while (statement.step()) {
                val groupId = statement.getText(0)
                membersByGroup[groupId]?.add(
                    json.decodeFromString<SourceCatalogEntry>(statement.getText(1)),
                )
            }
        }
        val groups = orderedIds.map { id ->
            CatalogRowsGroup(id, membersByGroup[id].orEmpty())
        }
        return CatalogRowsGroupPage(total, groups)
    }

    @Synchronized
    override fun close() {
        connection.close()
    }

    private fun buildWhereSql(): String {
        val clauses = mutableListOf<String>()
        if (filter.matchingKeys != null || filter.matchingGroupIds != null ||
            filter.matchingKeyRanks != null || filter.matchingGroupRanks != null
        ) {
            val searchClauses = buildList {
                if (filter.matchingKeys != null || filter.matchingKeyRanks != null) {
                    add("EXISTS (SELECT 1 FROM temp_search_keys k WHERE k.entry_key = catalog_rows.entry_key)")
                }
                if (filter.matchingGroupIds != null || filter.matchingGroupRanks != null) {
                    add(
                        "EXISTS (SELECT 1 FROM temp_search_groups g " +
                            "WHERE g.agm_group_id = catalog_rows.agm_group_id)",
                    )
                }
            }
            clauses += searchClauses.joinToString(" OR ", prefix = "(", postfix = ")")
        }
        if (!filter.excludedKeys.isNullOrEmpty()) {
            clauses +=
                "NOT EXISTS (SELECT 1 FROM temp_exclude_keys k WHERE k.entry_key = catalog_rows.entry_key)"
        }
        filter.tagTokens.forEach { token ->
            clauses += "instr(tag_tokens, ?) > 0"
            whereParams += token.trim().lowercase()
        }
        filter.excludedTagTokens.forEach { token ->
            clauses += "instr(tag_tokens, ?) = 0"
            whereParams += token.trim().lowercase()
        }
        filter.canonicalTagIds.forEach { id ->
            clauses += "instr(canonical_tags, ?) > 0"
            whereParams += packedValue(id.trim().lowercase())
        }
        filter.tagTokenGroups.forEach { group ->
            val tokens = group.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (tokens.isNotEmpty()) {
                clauses += tokens.joinToString(" OR ", prefix = "(", postfix = ")") {
                    "instr(tag_tokens, ?) > 0"
                }
                whereParams.addAll(tokens)
            }
        }
        filter.statusFilter?.let { tagId ->
            clauses += "source = '$SOURCE_F95ZONE' AND instr(numeric_tags, ?) > 0"
            whereParams += packedValue(tagId.toString())
        }
        filter.engineFilter?.let { tagId ->
            clauses += "source = '$SOURCE_F95ZONE' AND instr(numeric_tags, ?) > 0"
            whereParams += packedValue(tagId.toString())
        }
        filter.categoryFilter?.let { category ->
            clauses += "instr(categories, ?) > 0"
            whereParams += packedValue(category.lowercase())
        }
        filter.sourceFilter?.let { source ->
            clauses += "source = ?"
            whereParams += source
        }
        filter.platformFilter?.let { platform ->
            val alternatives = platformAlternatives(platform)
            clauses += alternatives.joinToString(" OR ", prefix = "(", postfix = ")") {
                "instr(platforms, ?) > 0"
            }
            whereParams.addAll(alternatives.map(::packedValue))
        }
        if (filter.minRating > 0f) {
            clauses += "sort_rating >= ?"
            whereParams += filter.minRating.toDouble()
        }
        if (filter.minPopularity > 0L) {
            clauses += "sort_popularity >= ?"
            whereParams += filter.minPopularity.toDouble()
        }
        filter.updatedSinceIso?.let {
            clauses += "sort_updated >= ?"
            whereParams += it
        }
        filter.publishedSinceIso?.let {
            clauses += "sort_newest >= ?"
            whereParams += it
        }
        val installedExpression =
            "(" +
                "EXISTS (SELECT 1 FROM temp_installed_keys i " +
                "WHERE i.entry_key = catalog_rows.entry_key) OR " +
                "EXISTS (SELECT 1 FROM temp_installed_groups g " +
                "WHERE g.agm_group_id = catalog_rows.agm_group_id) OR " +
                "(canonical_url <> '' AND EXISTS (SELECT 1 FROM temp_installed_urls u " +
                "WHERE u.canonical_url = catalog_rows.canonical_url))" +
                ")"
        if (filter.installedOnly) clauses += installedExpression
        if (filter.notInstalledOnly) clauses += "NOT $installedExpression"
        val wishlistExpression =
            "EXISTS (SELECT 1 FROM temp_wishlist_groups w " +
                "WHERE w.agm_group_id = catalog_rows.agm_group_id)"
        if (filter.wishlistOnly) clauses += wishlistExpression
        if (filter.notWishlistOnly) clauses += "NOT $wishlistExpression"
        val ignoredExpression =
            "EXISTS (SELECT 1 FROM temp_ignored_groups i " +
                "WHERE i.agm_group_id = catalog_rows.agm_group_id)"
        clauses += if (filter.ignoredOnly) ignoredExpression else "NOT $ignoredExpression"
        return if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
    }

    private fun buildOrderBySql(): String {
        val direction = if (filter.sortDesc) "DESC" else "ASC"
        val columns = when (filter.sortKey) {
            CatalogSortKey.Relevance ->
                "$rowRelevanceSql $direction, sort_rating DESC, sort_popularity DESC, title_lower"
            CatalogSortKey.Title -> "title_lower"
            CatalogSortKey.Rating -> "sort_rating $direction, sort_popularity"
            CatalogSortKey.Updated -> "sort_updated"
            CatalogSortKey.Newest -> "sort_newest"
            CatalogSortKey.Views,
            CatalogSortKey.Likes,
            -> "sort_popularity"
        }
        return "ORDER BY $columns $direction, row_id $direction"
    }

    /** ORDER BY for grouped queries: aggregates each sort column across the group's members so the
     *  group is ranked by its best/newest value (title uses the alphabetically-first member). */
    private fun buildGroupOrderBySql(): String {
        val direction = if (filter.sortDesc) "DESC" else "ASC"
        val columns = when (filter.sortKey) {
            CatalogSortKey.Relevance ->
                "$groupRelevanceSql $direction, MAX(sort_rating) DESC, " +
                    "MAX(sort_popularity) DESC, MIN(title_lower)"
            CatalogSortKey.Title -> "MIN(title_lower)"
            CatalogSortKey.Rating -> "MAX(sort_rating) $direction, MAX(sort_popularity)"
            CatalogSortKey.Updated -> "MAX(sort_updated)"
            CatalogSortKey.Newest -> "MAX(sort_newest)"
            CatalogSortKey.Views,
            CatalogSortKey.Likes,
            -> "MAX(sort_popularity)"
        }
        return "ORDER BY $columns $direction, agm_group_id $direction"
    }

    private val rowRelevanceSql: String
        get() =
            "(COALESCE((SELECT k.rank FROM temp_search_keys k " +
                "WHERE k.entry_key = catalog_rows.entry_key), 0.0) - " +
                "COALESCE((SELECT g.rank FROM temp_search_groups g " +
                "WHERE g.agm_group_id = catalog_rows.agm_group_id), 0.0))"

    private val groupRelevanceSql: String
        get() =
            "(MAX(COALESCE((SELECT k.rank FROM temp_search_keys k " +
                "WHERE k.entry_key = catalog_rows.entry_key), 0.0)) - " +
                "MIN(COALESCE((SELECT g.rank FROM temp_search_groups g " +
                "WHERE g.agm_group_id = catalog_rows.agm_group_id), 0.0)))"

    private fun populateRankedTempTable(
        table: String,
        column: String,
        values: Set<String>?,
        ranks: Map<String, Double>?,
    ) {
        connection.execSQL(
            "CREATE TEMP TABLE $table($column TEXT PRIMARY KEY, rank REAL NOT NULL) WITHOUT ROWID",
        )
        val entries = when {
            ranks != null -> ranks
            values != null -> values.associateWith { 0.0 }
            else -> emptyMap()
        }
        if (entries.isEmpty()) return
        connection.prepare(
            "INSERT OR REPLACE INTO $table($column, rank) VALUES (?, ?)",
        ).use { statement ->
            entries.forEach { (value, rank) ->
                statement.reset()
                statement.clearBindings()
                statement.bindText(1, value)
                statement.bindDouble(2, rank)
                statement.step()
            }
        }
    }

    private fun populateTempTable(
        table: String,
        column: String,
        values: Set<String>?,
    ) {
        connection.execSQL("CREATE TEMP TABLE $table($column TEXT PRIMARY KEY) WITHOUT ROWID")
        if (values.isNullOrEmpty()) return
        connection.prepare(
            "INSERT OR IGNORE INTO $table($column) VALUES (?)",
        ).use { statement ->
            values.forEach { value ->
                statement.reset()
                statement.clearBindings()
                statement.bindText(1, value)
                statement.step()
            }
        }
    }

    private fun bindAll(statement: SQLiteStatement, values: List<Any>) {
        values.forEachIndexed { index, value ->
            when (value) {
                is String -> statement.bindText(index + 1, value)
                is Double -> statement.bindDouble(index + 1, value)
                else -> error("Unsupported catalog rows parameter: ${value::class.java.name}")
            }
        }
    }

    private fun packedValue(value: String): String = "$valueSeparator$value$valueSeparator"

    private fun platformAlternatives(platform: String): List<String> = when (platform) {
        "Windows" -> listOf("windows", "windows/pc")
        "Android" -> listOf("android")
        "Mac" -> listOf("mac", "macos")
        "Linux" -> listOf("linux")
        else -> listOf(platform.lowercase())
    }
}
