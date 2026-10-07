package com.example.f95updater

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogGroupCardsTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Test
    fun parsesGroupCardsEnvelopeWithExtraServerFields() {
        val body = """
            {"schemaVersion":2,"generatedAt":"2026-08-02","count":1,"cards":[
              {"agmGroupId":"g_abc","title":"VoidBound","canonicalName":"voidbound",
               "sources":["f95zone","kimochi"],"sourceCount":2,
               "latestRelease":{"source":"f95zone","sourceId":"1","versionText":"1.2"},
               "versions":[{"source":"f95zone","sourceId":"1","versionText":"1.2"}],
               "canonicalTags":["engine:renpy","theme:ntr"],"engine":"RENPY",
               "suggestedRunner":"JoiPlay","rating":4.5,
               "identity":{"game":["steam:5"],"creator":[]},"futureField":123}
            ]}
        """.trimIndent()
        val env = json.decodeFromString(CatalogGroupCardsEnvelope.serializer(), body)
        assertEquals(1, env.cards.size)
        val card = env.cards.first()
        assertEquals("g_abc", card.agmGroupId)
        assertEquals(2, card.sourceCount)
        assertEquals("RENPY", card.engine)
        assertEquals("f95zone", card.latestRelease?.source)
        assertTrue(card.canonicalTags.contains("theme:ntr"))
    }

    @Test
    fun parsesCanonicalTaxonomyAndGroupsByFacet() {
        val body = """
            {"schemaVersion":2,"facets":["engine","theme"],"tags":[
              {"id":"engine:renpy","facet":"engine","label":"Ren'Py"},
              {"id":"theme:ntr","facet":"theme","label":"NTR"},
              {"id":"theme:harem","facet":"theme","label":"Harem"}]}
        """.trimIndent()
        val tax = json.decodeFromString(CanonicalTagTaxonomy.serializer(), body)
        val byFacet = tax.byFacet()
        assertEquals(1, byFacet["engine"]?.size)
        assertEquals(2, byFacet["theme"]?.size)
        assertEquals("NTR", tax.labelOf("theme:ntr"))
    }

    private fun card(id: String, title: String, tags: List<String>) = CatalogGroupCard(
        agmGroupId = id, title = title, canonicalName = title.lowercase(), canonicalTags = tags,
    )

    @Test
    fun filtersByTagsAndQuery() {
        val cards = listOf(
            card("g1", "VoidBound", listOf("engine:renpy", "theme:ntr")),
            card("g2", "Harem Days", listOf("engine:rpgm", "theme:harem")),
            card("g3", "Void Quest", listOf("engine:renpy", "theme:harem")),
        )
        // tag AND: both renpy + harem => only g3
        assertEquals(
            listOf("g3"),
            CatalogGroupFiltering.filter(cards, setOf("engine:renpy", "theme:harem"), "")
                .map { it.agmGroupId },
        )
        // query substring
        assertEquals(
            listOf("g1", "g3"),
            CatalogGroupFiltering.filter(cards, emptySet(), "void").map { it.agmGroupId },
        )
        // no filters => all
        assertEquals(3, CatalogGroupFiltering.filter(cards, emptySet(), "").size)
    }
}
