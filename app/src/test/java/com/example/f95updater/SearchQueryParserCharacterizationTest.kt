package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Characterization tests pinning the CURRENT behavior of [parseSearchQuery] /
 * [parseTagFilters] / [ParsedQuery] as implemented in MainActivity.kt. These tests intentionally
 * document existing behavior (including quirky edge cases) rather than desired behavior, to guard
 * against accidental changes during the behavior-preserving refactor.
 */
class SearchQueryParserCharacterizationTest {

    @Test
    fun blankEmptyOrWhitespaceOnlyInputYieldsEmptyParsedQuery() {
        assertEquals(ParsedQuery("", emptyList()), parseSearchQuery(""))
        assertEquals(ParsedQuery("", emptyList()), parseSearchQuery("   "))
        assertEquals(ParsedQuery("", emptyList()), parseSearchQuery("\t\n  "))
    }

    @Test
    fun plainFreeTextWithCollapsedInternalWhitespaceHasNoTags() {
        val parsed = parseSearchQuery("hello   world\tfoo\n bar")

        assertEquals("hello world foo bar", parsed.freeText)
        assertEquals(emptyList<String>(), parsed.tags)
    }

    @Test
    fun singleValidTagTokenIsExtractedAndExcludedFromFreeText() {
        val parsed = parseSearchQuery("tag:romance")

        assertEquals("", parsed.freeText)
        assertEquals(listOf("romance"), parsed.tags)
    }

    @Test
    fun tagPrefixIsCaseInsensitiveAndValueIsLowercased() {
        assertEquals(listOf("romance"), parseSearchQuery("TAG:Romance").tags)
        assertEquals(listOf("romance"), parseSearchQuery("Tag:Romance").tags)
        assertEquals(listOf("romance"), parseSearchQuery("tAg:ROMANCE").tags)
    }

    @Test
    fun exactlyFourCharacterTagPrefixIsNotTreatedAsTagButFiveCharsIs() {
        // "tag:" has length 4, which fails the `token.length > 4` guard, so it is NOT
        // recognized as a tag token and instead falls through to free text verbatim.
        val bareToken = parseSearchQuery("tag:")
        assertEquals("tag:", bareToken.freeText)
        assertEquals(emptyList<String>(), bareToken.tags)

        // "tag:x" has length 5 (> 4) and is recognized as a tag with value "x".
        val oneCharValue = parseSearchQuery("tag:x")
        assertEquals("", oneCharValue.freeText)
        assertEquals(listOf("x"), oneCharValue.tags)
    }

    @Test
    fun tagTokenWhoseValueTrimsToEmptyIsDroppedEntirelyFromBothTagsAndFreeText() {
        // A regular space cannot appear inside a single "tag:..." token because the raw
        // string is first split on Regex("\\s+"), so any ASCII whitespace after the "tag:"
        // prefix would already have separated it into its own token. However a NO-BREAK
        // SPACE (U+00A0) is NOT matched by the ASCII `\s` character class used for the split,
        // so it survives as part of the same token, while Kotlin's String.trim() DOES treat
        // U+00A0 as whitespace (via Character.isSpaceChar) and strips it. The net effect: the
        // token "tag:\u00A0" (length 5, so it passes the `> 4` guard and the "tag:" prefix
        // check) has its value trimmed down to an empty string, so it is silently dropped:
        // it is NOT added to tags, and — because it already matched the tag branch — it is
        // also NOT added to freeText. This differs from the naive assumption that a token
        // failing to become a tag always falls back to free text.
        val parsed = parseSearchQuery("tag:\u00A0")

        assertEquals("", parsed.freeText)
        assertEquals(emptyList<String>(), parsed.tags)
    }

    @Test
    fun mixedFreeTextAndMultipleTagsPreservesTagOrderAndFreeTextOrder() {
        val parsed = parseSearchQuery("alpha tag:one beta TAG:Two gamma tag:three")

        assertEquals("alpha beta gamma", parsed.freeText)
        assertEquals(listOf("one", "two", "three"), parsed.tags)
    }

    @Test
    fun leadingAndTrailingWhitespaceInWholeQueryIsTrimmedFromFreeText() {
        val parsed = parseSearchQuery("   leading and trailing   ")

        assertEquals("leading and trailing", parsed.freeText)
        assertEquals(emptyList<String>(), parsed.tags)
    }

    @Test
    fun parseTagFiltersDelegatesToParseSearchQueryTags() {
        val raw = "some words tag:action more TAG:Fun-Stuff words"

        assertEquals(parseSearchQuery(raw).tags, parseTagFilters(raw))
        assertEquals(listOf("action", "fun-stuff"), parseTagFilters(raw))
    }
}
