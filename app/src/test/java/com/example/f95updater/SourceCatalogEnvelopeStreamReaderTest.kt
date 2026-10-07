package com.example.f95updater

import java.io.File
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceCatalogEnvelopeStreamReaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readsPlainEntriesOneAtATimeWithEscapesAndUnicode() {
        val file = writeEnvelope(
            """
            {
              "schemaVersion": 1,
              "generatedAt": "2026-07-16T00:00:00Z",
              "source": "example",
              "count": 2,
              "ignored": {"nested": ["value", {"still": "valid"}]},
              "entries": [
                {"source":"example","sourceId":"1","canonicalUrl":"https://example.test/1","title":"Quote: \"{brace}\" \\ path"},
                {"source":"example","sourceId":"2","canonicalUrl":"https://example.test/2","title":"少女の旅 😀"}
              ]
            }
            """.trimIndent(),
        )
        val entries = mutableListOf<SourceCatalogEntry>()

        val result = SourceCatalogEnvelopeStreamReader.read(file, entries::add)

        assertEquals(2, result.declaredCount)
        assertEquals(2, result.emittedCount)
        assertEquals("Quote: \"{brace}\" \\ path", entries[0].title)
        assertEquals("少女の旅 😀", entries[1].title)
    }

    @Test
    fun readsGzipEnvelope() {
        val file = writeEnvelope(
            """{"generatedAt":"t","source":"gzip","count":1,"entries":[{"source":"gzip","sourceId":"1","canonicalUrl":"","title":"Compressed"}]}""",
            gzip = true,
        )
        val titles = mutableListOf<String>()

        val result = SourceCatalogEnvelopeStreamReader.read(file, { titles += it.title })

        assertEquals("gzip", result.source)
        assertEquals(1, result.emittedCount)
        assertEquals(listOf("Compressed"), titles)
    }

    @Test
    fun readsEmptyEntriesArray() {
        val file = writeEnvelope(
            """{"generatedAt":"t","source":"empty","count":0,"entries":[]}""",
        )

        val result = SourceCatalogEnvelopeStreamReader.read(file, { error("unexpected entry") })

        assertEquals(0, result.declaredCount)
        assertEquals(0, result.emittedCount)
    }

    @Test
    fun skipsMalformedEntriesButKeepsTheCatalog() {
        // One valid entry and one malformed entry (bad value). The bad entry is skipped and
        // the catalog is still built rather than discarded wholesale.
        val file = writeEnvelope(
            """{"generatedAt":"t","source":"bad","count":2,"entries":[""" +
                """{"source":"bad","sourceId":"1","canonicalUrl":"","title":"Good"},""" +
                """{"source":}]}""",
            name = "one-bad-entry.json",
        )
        val kept = mutableListOf<SourceCatalogEntry>()

        val result = SourceCatalogEnvelopeStreamReader.read(file, kept::add)

        assertEquals(listOf("Good"), kept.map { it.title })
        assertEquals(1, result.emittedCount)
        assertEquals(2, result.declaredCount)
    }

    @Test
    fun rejectsTruncatedInput() {
        val truncated = writeEnvelope(
            """{"generatedAt":"t","source":"bad","count":1,"entries":[{"source":"bad","sourceId":"1","canonicalUrl":"","title":"unfinished"}""",
            name = "truncated.json",
        )

        val error = runCatching {
            SourceCatalogEnvelopeStreamReader.read(truncated, {})
        }.exceptionOrNull()

        assertEquals(true, error is SourceCatalogEnvelopeStreamException)
        assertEquals(true, error?.message.orEmpty().contains("Malformed SourceCatalogEnvelope"))
    }

    @Test
    fun observesCancellationBetweenEntries() {
        val file = writeEnvelope(
            """{"generatedAt":"t","source":"cancel","count":2,"entries":[{"source":"cancel","sourceId":"1","canonicalUrl":"","title":"One"},{"source":"cancel","sourceId":"2","canonicalUrl":"","title":"Two"}]}""",
        )
        var emitted = 0

        val error = runCatching {
            SourceCatalogEnvelopeStreamReader.read(
                file = file,
                onEntry = { emitted++ },
                checkCancelled = {
                    if (emitted == 1) error("cancelled")
                },
            )
        }.exceptionOrNull()

        assertEquals(true, error is IllegalStateException)
        assertEquals(1, emitted)
    }

    private fun writeEnvelope(
        contents: String,
        gzip: Boolean = false,
        name: String = if (gzip) "catalog.json.gz" else "catalog.json",
    ): File =
        File(temporaryFolder.root, name).also { file ->
            if (gzip) {
                GZIPOutputStream(file.outputStream()).bufferedWriter(Charsets.UTF_8).use { it.write(contents) }
            } else {
                file.writeText(contents, Charsets.UTF_8)
            }
        }
}
