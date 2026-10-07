package com.example.f95updater

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogSynopsisDatabaseTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun serverSchemaUsesNativePorterStemmingAndQuotedPhrasesByGroup() {
        val file = File(temporaryFolder.root, "catalog.sqlite")
        BundledSQLiteDriver().open(
            file.absolutePath,
            SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE or SQLITE_OPEN_FULLMUTEX,
        ).use { connection ->
            connection.execSQL("CREATE TABLE search_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            connection.execSQL("INSERT INTO search_meta(key,value) VALUES ('schema','9')")
            connection.execSQL(
                "CREATE TABLE synopsis_documents(" +
                    "row_id INTEGER PRIMARY KEY, agm_group_id TEXT NOT NULL UNIQUE, " +
                    "source_doc_key TEXT NOT NULL, source_fingerprint TEXT NOT NULL, " +
                    "synopsis_en TEXT NOT NULL)",
            )
            connection.execSQL(
                "CREATE VIRTUAL TABLE synopsis_fts USING fts5(" +
                    "synopsis_en, agm_group_id UNINDEXED, " +
                    "content='synopsis_documents', content_rowid='row_id', " +
                    "tokenize='porter unicode61 remove_diacritics 2')",
            )
            connection.execSQL(
                "INSERT INTO synopsis_documents VALUES " +
                    "(1,'g_station','f95zone:1','hash','A detective investigates the old space station.')," +
                    "(2,'g_scifi','f95zone:2','hash','A sci-fi colony survives on Mars.')," +
                    "(3,'g_cant','f95zone:3','hash','You can''t leave the haunted house.')," +
                    "(4,'g_detective','f95zone:4','hash','Detective detective detective mystery.')",
            )
            connection.execSQL(
                "INSERT INTO synopsis_fts(rowid,synopsis_en,agm_group_id) VALUES " +
                    "(1,'A detective investigates the old space station.','g_station')," +
                    "(2,'A sci-fi colony survives on Mars.','g_scifi')," +
                    "(3,'You can''t leave the haunted house.','g_cant')," +
                    "(4,'Detective detective detective mystery.','g_detective')",
            )
        }

        assertTrue(CatalogSynopsisDatabase.isCompatible(file))
        assertEquals(
            CatalogGroupSynopsis(
                synopsis = "A sci-fi colony survives on Mars.",
                sourceDocKey = "f95zone:2",
            ),
            CatalogSynopsisDatabase.groupSynopsis(file, "g_scifi"),
        )
        assertEquals(setOf("g_station"), CatalogSynopsisDatabase.searchGroupRanks(file, "investigate").keys)
        assertEquals(setOf("g_station"), CatalogSynopsisDatabase.searchGroupRanks(file, "\"space station\"").keys)
        assertEquals(emptySet<String>(), CatalogSynopsisDatabase.searchGroupRanks(file, "\"station space\"").keys)
        assertEquals(setOf("g_scifi"), CatalogSynopsisDatabase.searchGroupRanks(file, "sci-fi").keys)
        assertEquals(setOf("g_cant"), CatalogSynopsisDatabase.searchGroupRanks(file, "can't").keys)
        val detectiveRanks = CatalogSynopsisDatabase.searchGroupRanks(file, "detective")
        assertTrue(detectiveRanks.getValue("g_detective") < detectiveRanks.getValue("g_station"))
    }

    @Test
    fun nativeQueryQuotesOrdinaryTokensAndPreservesExplicitPhrases() {
        assertEquals("\"sci-fi\" \"can't\"", nativeFts5Query("sci-fi can't"))
        assertEquals("\"space station\" \"detective\"", nativeFts5Query("\"space station\" detective"))
        assertEquals("\"\"\"unfinished\"", nativeFts5Query("\"unfinished"))
    }

    @Test
    fun incompatibleOrMissingDatabaseIsRejected() {
        assertFalse(CatalogSynopsisDatabase.isCompatible(File(temporaryFolder.root, "missing.sqlite")))
    }
}
