package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchQueryParserExclusionTest {
    @Test
    fun excludedWordIsSeparatedFromFreeText() {
        val p = parseSearchQuery("maid -ntr castle")
        assertEquals("maid castle", p.freeText)
        assertEquals(listOf("ntr"), p.excludedText)
        assertEquals(emptyList<String>(), p.tags)
        assertEquals(emptyList<String>(), p.excludedTags)
    }

    @Test
    fun excludedTagIsSeparatedAndLowercased() {
        val p = parseSearchQuery("tag:romance -tag:NTR -tag:Corruption")
        assertEquals(listOf("romance"), p.tags)
        assertEquals(listOf("ntr", "corruption"), p.excludedTags)
        assertEquals("", p.freeText)
        assertEquals(emptyList<String>(), p.excludedText)
    }

    @Test
    fun bareDashTagPrefixIsExcludedTagOnlyWhenLongerThanPrefix() {
        // "-tag:" is length 5, value empty -> dropped from both.
        val empty = parseSearchQuery("-tag:")
        assertEquals(emptyList<String>(), empty.excludedTags)
        assertEquals("", empty.freeText)
        // "-tag:x" length 6 -> excluded tag "x".
        val one = parseSearchQuery("-tag:x")
        assertEquals(listOf("x"), one.excludedTags)
    }

    @Test
    fun mixedIncludeAndExcludePreservesOrderAndBuckets() {
        val p = parseSearchQuery("alpha -beta tag:one -tag:two gamma -delta")
        assertEquals("alpha gamma", p.freeText)
        assertEquals(listOf("one"), p.tags)
        assertEquals(listOf("beta", "delta"), p.excludedText)
        assertEquals(listOf("two"), p.excludedTags)
    }

    @Test
    fun loneDashStaysFreeText() {
        val p = parseSearchQuery("a - b")
        assertEquals("a - b", p.freeText)
        assertEquals(emptyList<String>(), p.excludedText)
    }
}
