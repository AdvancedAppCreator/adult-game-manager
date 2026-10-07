package com.example.f95updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSearchModesTest {
    @Test
    fun normalModeIsSubstringCaseInsensitive() {
        val p = CatalogSearchModes.titlePredicate("hiki", CatalogSearchMode.Normal)!!
        assertTrue(p("everyday life with hikikomori sister"))
        assertTrue(p("HIKIKOMORI"))
        assertFalse(p("unrelated title"))
    }

    @Test
    fun wholeWordModeRespectsBoundaries() {
        val p = CatalogSearchModes.titlePredicate("maid", CatalogSearchMode.WholeWord)!!
        assertTrue(p("the maid returns"))
        assertFalse(p("mermaid lagoon"))
    }

    @Test
    fun regexModeMatchesPatternAndRejectsInvalid() {
        val p = CatalogSearchModes.titlePredicate("sist(er|ers)\\b", CatalogSearchMode.Regex)!!
        assertTrue(p("hikikomori sister"))
        assertTrue(p("two sisters"))
        assertNull(CatalogSearchModes.titlePredicate("(unclosed", CatalogSearchMode.Regex))
    }

    @Test
    fun blankTermYieldsNoPredicate() {
        assertNull(CatalogSearchModes.titlePredicate("   ", CatalogSearchMode.Normal))
    }

    @Test
    fun anyTitlePredicateMatchesUnionAndIsNullWhenEmpty() {
        assertNull(CatalogSearchModes.anyTitlePredicate(emptyList(), CatalogSearchMode.Normal))
        val p = CatalogSearchModes.anyTitlePredicate(listOf("ntr", "corruption"), CatalogSearchMode.Normal)
        assertNotNull(p)
        assertTrue(p!!("some ntr game"))
        assertTrue(p("corruption path"))
        assertFalse(p("wholesome romance"))
    }

    @Test
    fun isValidRegex() {
        assertTrue(CatalogSearchModes.isValidRegex("a.*b"))
        assertFalse(CatalogSearchModes.isValidRegex("(unclosed"))
        assertFalse(CatalogSearchModes.isValidRegex("   "))
    }
}
