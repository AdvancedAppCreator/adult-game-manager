package com.example.f95updater

/** Free-text matching mode for catalog search. */
enum class CatalogSearchMode { Normal, WholeWord, Regex }

object CatalogSearchModes {
    /**
     * Case-insensitive predicate over a (lowercased) catalog title for [term] under [mode].
     * Returns null when the term is blank or, for [CatalogSearchMode.Regex], the pattern is invalid.
     */
    fun titlePredicate(term: String, mode: CatalogSearchMode): ((String) -> Boolean)? {
        val t = term.trim()
        if (t.isEmpty()) return null
        return when (mode) {
            CatalogSearchMode.Normal, CatalogSearchMode.WholeWord -> {
                val wholeWord = mode == CatalogSearchMode.WholeWord
                ({ title: String -> SaveSearchMatcher.matches(title, t, wholeWord) })
            }
            CatalogSearchMode.Regex -> {
                val rx = runCatching { Regex(t, RegexOption.IGNORE_CASE) }.getOrNull() ?: return null
                ({ title: String -> rx.containsMatchIn(title) })
            }
        }
    }

    /** Predicate that matches when the title matches ANY of [terms] under [mode]. Null if none valid. */
    fun anyTitlePredicate(terms: List<String>, mode: CatalogSearchMode): ((String) -> Boolean)? {
        if (mode == CatalogSearchMode.Regex && terms.any { titlePredicate(it, mode) == null }) {
            return null
        }
        val preds = terms.mapNotNull { titlePredicate(it, mode) }
        if (preds.isEmpty()) return null
        return { title -> preds.any { it(title) } }
    }

    fun isValidRegex(pattern: String): Boolean =
        pattern.isNotBlank() && runCatching { Regex(pattern) }.isSuccess
}
