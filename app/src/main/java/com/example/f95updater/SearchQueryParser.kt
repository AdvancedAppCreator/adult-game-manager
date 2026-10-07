package com.example.f95updater

/** Parsed search box: separates `tag:`/`-tag:` terms and `-word` exclusions from included free-text. */
data class ParsedQuery(
    val freeText: String,
    val tags: List<String>,
    val excludedText: List<String> = emptyList(),
    val excludedTags: List<String> = emptyList(),
)

fun parseSearchQuery(raw: String): ParsedQuery {
    if (raw.isBlank()) return ParsedQuery("", emptyList())
    val tags = mutableListOf<String>()
    val excludedTags = mutableListOf<String>()
    val excludedText = mutableListOf<String>()
    val free = StringBuilder()
    raw.split(Regex("\\s+")).forEach { tokenRaw ->
        val tok = tokenRaw
        if (tok.isEmpty()) return@forEach
        when {
            tok.length > 5 && tok.startsWith("-tag:", ignoreCase = true) -> {
                val t = tok.substring(5).trim().lowercase()
                if (t.isNotEmpty()) excludedTags.add(t)
            }
            tok.length > 4 && tok.startsWith("tag:", ignoreCase = true) -> {
                val t = tok.substring(4).trim().lowercase()
                if (t.isNotEmpty()) tags.add(t)
            }
            tok.length > 1 && tok.startsWith("-") -> {
                val w = tok.substring(1).trim()
                if (w.isNotEmpty()) excludedText.add(w)
            }
            else -> {
                if (free.isNotEmpty()) free.append(' ')
                free.append(tok)
            }
        }
    }
    return ParsedQuery(
        freeText = free.toString().trim(),
        tags = tags,
        excludedText = excludedText,
        excludedTags = excludedTags,
    )
}

fun parseTagFilters(raw: String): List<String> = parseSearchQuery(raw).tags
