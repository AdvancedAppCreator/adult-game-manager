package com.example.f95updater

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import java.io.File

internal object CatalogSynopsisDatabase {
    fun isCompatible(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        return runCatching {
            BundledSQLiteDriver().open(
                file.absolutePath,
                SQLITE_OPEN_READONLY or SQLITE_OPEN_FULLMUTEX,
            ).use { connection ->
                val meta = connection.prepare(
                    "SELECT value FROM search_meta WHERE key = 'schema'",
                ).use { statement ->
                    if (statement.step()) statement.getText(0) else null
                }
                if (meta != "9") return@use false
                val tables = connection.prepare(
                    "SELECT name FROM sqlite_master WHERE name IN ('synopsis_documents','synopsis_fts')",
                ).use { statement ->
                    buildSet {
                        while (statement.step()) add(statement.getText(0))
                    }
                }
                tables == setOf("synopsis_documents", "synopsis_fts")
            }
        }.getOrDefault(false)
    }

    fun searchGroupRanks(file: File, query: String): Map<String, Double> {
        val matchQuery = nativeFts5Query(query)
        if (matchQuery.isEmpty() || !isCompatible(file)) return emptyMap()
        return BundledSQLiteDriver().open(
            file.absolutePath,
            SQLITE_OPEN_READONLY or SQLITE_OPEN_FULLMUTEX,
        ).use { connection ->
            connection.prepare(
                """
                SELECT agm_group_id
                     , bm25(synopsis_fts)
                FROM synopsis_fts
                WHERE synopsis_fts MATCH ?
                ORDER BY bm25(synopsis_fts)
                """.trimIndent(),
            ).use { statement ->
                statement.bindText(1, matchQuery)
                buildMap {
                    while (statement.step()) put(statement.getText(0), statement.getDouble(1))
                }
            }
        }
    }

    fun groupSynopsis(file: File, agmGroupId: String): CatalogGroupSynopsis? {
        if (agmGroupId.isBlank() || !isCompatible(file)) return null
        return BundledSQLiteDriver().open(
            file.absolutePath,
            SQLITE_OPEN_READONLY or SQLITE_OPEN_FULLMUTEX,
        ).use { connection ->
            connection.prepare(
                """
                SELECT synopsis_en, source_doc_key
                FROM synopsis_documents
                WHERE agm_group_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.bindText(1, agmGroupId)
                if (!statement.step()) {
                    null
                } else {
                    CatalogGroupSynopsis(
                        synopsis = statement.getText(0),
                        sourceDocKey = statement.getText(1),
                    )
                }
            }
        }
    }
}

internal data class CatalogGroupSynopsis(
    val synopsis: String,
    val sourceDocKey: String,
) {
    val source: String get() = sourceDocKey.substringBefore(':')
}

internal fun nativeFts5Query(query: String): String {
    val text = query.trim()
    if (text.isEmpty()) return ""
    val expressions = mutableListOf<String>()
    var index = 0
    while (index < text.length) {
        while (index < text.length && text[index].isWhitespace()) index++
        if (index >= text.length) break
        if (text[index] == '"') {
            val closingQuote = text.indexOf('"', startIndex = index + 1)
            if (closingQuote >= 0) {
                text.substring(index + 1, closingQuote)
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?.let { expressions += quoteFts5Literal(it) }
                index = closingQuote + 1
                continue
            }
        }
        val end = text.indexOfFirstFrom(index, Char::isWhitespace)
        val tokenEnd = if (end < 0) text.length else end
        expressions += quoteFts5Literal(text.substring(index, tokenEnd))
        index = tokenEnd
    }
    return expressions.joinToString(" ")
}

private fun quoteFts5Literal(value: String): String = "\"${value.replace("\"", "\"\"")}\""

private inline fun String.indexOfFirstFrom(startIndex: Int, predicate: (Char) -> Boolean): Int {
    for (index in startIndex until length) {
        if (predicate(this[index])) return index
    }
    return -1
}
