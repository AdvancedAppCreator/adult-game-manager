package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTitleTranslatorTest {
    @Test
    fun cacheKeyIsStableAndVariesByTitleAndTargetLanguage() {
        val key = CatalogTitleTranslator.cacheKey("otomi:123", "Original title", "en")

        assertEquals(
            "7e30a85e6d44e954f2619c29bda969a8cc23434f35cf29fb040dfcc9a4de6767",
            key,
        )
        assertEquals(
            key,
            CatalogTitleTranslator.cacheKey("otomi:123", "Original title", "en"),
        )
        assertNotEquals(
            key,
            CatalogTitleTranslator.cacheKey("otomi:123", "Changed title", "en"),
        )
        assertNotEquals(
            key,
            CatalogTitleTranslator.cacheKey("otomi:123", "Original title", "es"),
        )
        assertNotEquals(
            key,
            CatalogTitleTranslator.cacheKey("otomi:123", "Original title", "en", force = true),
        )
    }

    @Test
    fun mixedSynopsisSegmentsPreserveOriginalTextBoundaries() {
        val text = "This paragraph is English. これは日本語です。\nOtro párrafo."
        val segments = translationSegments(text)

        assertTrue(segments.size >= 2)
        assertEquals(text, segments.joinToString(""))
    }
}
