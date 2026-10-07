package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogMappingSnapshotTest {
    @Test
    fun mappedCatalogIdResolvesNonF95GameWithThumbnail() {
        val game = CatalogGame(
            thread_id = -42,
            title = "PC Game",
            source = "kimochi",
            sourceId = "pc-game",
            cover = "https://example.test/full.jpg",
            thumbnailUrl = "https://example.test/thumb.jpg",
        )
        val mapping = AppMapping(
            packageName = "winlator:pc-game",
            mappedCatalogId = game.thread_id,
            mappedCatalogSource = game.source,
            mappedCatalogSourceId = game.sourceId,
            mappedCatalogTitle = game.title,
        )

        assertEquals(game, mappedCatalogGame(mapping, mapOf(game.thread_id to game)))
    }

    @Test
    fun catalogSnapshotRetainsImagesBeforeCatalogMapLoads() {
        val game = CatalogGame(
            thread_id = -7,
            title = "PC Game",
            source = "otomi",
            sourceId = "7",
            cover = "https://example.test/full.jpg",
            thumbnailUrl = "https://example.test/thumb.jpg",
        )
        val mapping = AppMapping(packageName = "winlator:7").withCatalogSnapshot(game)

        val snapshot = checkNotNull(mappedCatalogGame(mapping, null))

        assertEquals(game.cover, snapshot.cover)
        assertEquals(game.thumbnailUrl, snapshot.thumbnailUrl)
        assertEquals(game.thumbnailUrl, catalogImageUrls(snapshot).thumbnailUrl)
    }
}
