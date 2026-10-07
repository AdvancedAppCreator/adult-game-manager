package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogGroupPresentationTest {

    private fun entry(
        source: String,
        sourceId: String,
        title: String,
        version: String? = null,
        rating: Double? = null,
        canonicalTags: List<String> = emptyList(),
        cover: String? = null,
    ) = SourceCatalogEntry(
        source = source,
        sourceId = sourceId,
        canonicalUrl = "https://example.com/$source/$sourceId",
        title = title,
        versionText = version,
        rating = rating,
        canonicalTags = canonicalTags,
        coverUrl = cover,
    )

    @Test
    fun aggregatesNewestVersionBestRatingAndAllSources() {
        SourceRegistry.update(
            listOf(
                CatalogSourceInfo(id = "f95zone", displayName = "F95zone", priority = 100),
                CatalogSourceInfo(id = "dlsite", displayName = "DLsite", priority = 10),
            ),
        )
        val members = listOf(
            entry("dlsite", "2", "STS DL", version = "1.0", rating = 4.9, canonicalTags = listOf("art:2d")),
            entry("f95zone", "1", "Summertime Saga", version = "21.0", rating = 4.5,
                canonicalTags = listOf("engine:renpy"), cover = "https://c/x.jpg"),
        )

        val display = CatalogGroupPresentation.of("g_sts", members)

        // Representative is the highest-priority source (f95zone).
        assertEquals("Summertime Saga", display.title)
        // Newest version wins across sources.
        assertEquals("21.0", display.versionText)
        // Best rating across sources.
        assertEquals(4.9, display.rating!!, 0.0001)
        // Every source is surfaced, ordered by priority.
        assertEquals(listOf("f95zone", "dlsite"), display.sources)
        assertEquals(2, display.sourceCount)
        // Canonical tags are the union across members.
        assertEquals(setOf("engine:renpy", "art:2d"), display.canonicalTags.toSet())
        assertEquals("https://c/x.jpg", display.coverUrl)
    }

    @Test
    fun singleMemberGroupUsesThatMember() {
        val display = CatalogGroupPresentation.of("g_solo", listOf(entry("f95zone", "7", "Solo")))
        assertEquals("Solo", display.title)
        assertEquals(1, display.sourceCount)
    }
}
