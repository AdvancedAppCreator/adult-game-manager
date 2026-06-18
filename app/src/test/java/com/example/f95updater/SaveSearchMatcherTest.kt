package com.example.f95updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveSearchMatcherTest {
    @Test
    fun substringSearchMatchesInsideLongerNumbers() {
        assertTrue(SaveSearchMatcher.matches("10", "1", wholeWord = false))
        assertTrue(SaveSearchMatcher.matches("chapter11", "1", wholeWord = false))
    }

    @Test
    fun wholeWordSearchDoesNotMatchInsideLongerNumbers() {
        assertTrue(SaveSearchMatcher.matches("1", "1", wholeWord = true))
        assertTrue(SaveSearchMatcher.matches("\"1\"", "1", wholeWord = true))
        assertFalse(SaveSearchMatcher.matches("10", "1", wholeWord = true))
        assertFalse(SaveSearchMatcher.matches("11", "1", wholeWord = true))
        assertFalse(SaveSearchMatcher.matches("chapter11", "1", wholeWord = true))
    }

    @Test
    fun wholeWordSearchMatchesPathSegmentsWithoutMatchingPartialNames() {
        assertTrue(SaveSearchMatcher.matches("store.money", "money", wholeWord = true))
        assertFalse(SaveSearchMatcher.matches("store.moneyTotal", "money", wholeWord = true))
    }

    @Test
    fun matchesAnyUsesAllFields() {
        assertTrue(
            SaveSearchMatcher.matchesAny(
                query = "1",
                wholeWord = true,
                fields = listOf("store.points", "1", "int"),
            )
        )
        assertFalse(
            SaveSearchMatcher.matchesAny(
                query = "1",
                wholeWord = true,
                fields = listOf("store.points", "10", "int"),
            )
        )
    }
}
