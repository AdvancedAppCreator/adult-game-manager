package com.example.f95updater

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogDetailsRepositoryTest {
    @Test
    fun shardUsesFirstSha256Byte() {
        assertEquals("a6", catalogDetailsShard("123"))
        assertEquals("ba", catalogDetailsShard("some-game"))
    }

    @Test
    fun detailsUrlReplacesShardPlaceholder() {
        assertEquals(
            "https://example.test/details/a6.json.gz",
            catalogDetailsUrl(
                "https://example.test/details/{shard}.json.gz",
                "a6",
                "2026-07-14T00:00:00Z",
            ),
        )
        assertEquals(
            "https://example.test/details/a6.json.gz?v=2026-07-14T00%3A00%3A00Z",
            catalogDetailsUrl(
                "https://example.test/details/{shard}.json.gz?v={generation}",
                "a6",
                "2026-07-14T00:00:00Z",
            ),
        )
        assertNull(
            catalogDetailsUrl(
                "https://example.test/details.json.gz",
                "a6",
                "2026-07-14T00:00:00Z",
            ),
        )
    }

    @Test
    fun compressedShardDecodesSynopsis() {
        val raw = """
            {
              "schemaVersion": 1,
              "source": "dikgames",
              "shard": "a6",
              "entries": {
                "123": {
                  "synopsis": "A test synopsis.",
                  "updatedAt": "2026-07-14T00:00:00Z"
                }
              }
            }
        """.trimIndent().toByteArray()
        val compressed = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(raw) }
            output.toByteArray()
        }

        val decoded = decodeCatalogDetailsShard(compressed)

        assertEquals("dikgames", decoded.source)
        assertEquals("A test synopsis.", decoded.entries["123"]?.synopsis)
    }
}
