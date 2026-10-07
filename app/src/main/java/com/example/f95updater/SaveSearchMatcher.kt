package com.example.f95updater

internal object SaveSearchMatcher {
    fun matchesAny(
        query: String,
        wholeWord: Boolean,
        fields: Iterable<String>,
    ): Boolean {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return true
        return fields.any { field -> matches(field, normalizedQuery, wholeWord) }
    }

    fun matches(field: String, query: String, wholeWord: Boolean): Boolean {
        if (!wholeWord) return field.contains(query, ignoreCase = true)
        if (query.isEmpty()) return true
        var start = field.indexOf(query, ignoreCase = true)
        while (start >= 0) {
            val end = start + query.length
            val beforeBoundary = start == 0 || !field[start - 1].isLetterOrDigit()
            val afterBoundary = end == field.length || !field[end].isLetterOrDigit()
            if (beforeBoundary && afterBoundary) return true
            start = field.indexOf(query, startIndex = start + 1, ignoreCase = true)
        }
        return false
    }
}
