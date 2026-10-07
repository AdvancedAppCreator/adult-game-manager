package com.example.f95updater

object VersionCompare {
    private val bracketRegex = Regex("""\[([^\]]+)]""")
    private val numericTokenRegex = Regex("""\d+""")

    /** Extract a comparable version string from a free-form thread title.
     *  Strategy: prefer the first non-tag bracketed segment (typically v0.6.15a1),
     *  else return the title trimmed/normalized. Any change vs. previous value = update. */
    fun extract(raw: String): String? {
        if (raw.isBlank()) return null
        val title = raw.substringBefore(" | F95zone").substringBefore("| F95Zone").trim()
        val brackets = bracketRegex.findAll(title).map { it.groupValues[1].trim() }.toList()
        val versionish = brackets.firstOrNull { it.any(Char::isDigit) }
        if (!versionish.isNullOrBlank()) return versionish.removePrefix("v").removePrefix("V").trim()
        val stripped = title.replace(bracketRegex, "").trim().replace(Regex("\\s+"), " ")
        return stripped.ifBlank { title }
    }

    /**
     * Returns true if the installed APK's version reasonably matches the latest source
     * version. Comparison is **structural**: both strings are reduced to a sequence of
     * numeric components (split on any non-digit boundary), then compared element by
     * element. Equal sequences = current.
     *
     * Examples:
     *   installed "0.4",     latest "0.4.7b"  -> [0,4]    vs [0,4,7]  -> NOT current
     *   installed "0.4.7",   latest "0.4.7b"  -> [0,4,7]  vs [0,4,7]  -> current
     *   installed "v1.2.3",  latest "1.2.3-pc"-> [1,2,3]  vs [1,2,3]  -> current
     *   installed "0.4.7b",  latest "0.4.7a"  -> [0,4,7]  vs [0,4,7]  but suffix differs
     *                                            -> NOT current (alpha suffix on the LAST numeric
     *                                                token matters: "0.4.7a" ≠ "0.4.7b")
     *
     * Falls back to normalized exact equality when either side has no numeric tokens.
     */
    fun matchesInstalled(seen: String?, installedVersionName: String): Boolean {
        if (seen.isNullOrBlank() || installedVersionName.isBlank()) return false
        val seenNums = numericTokens(seen)
        val instNums = numericTokens(installedVersionName)
        if (seenNums.isEmpty() || instNums.isEmpty()) {
            // No numbers either side — fall back to normalized alphanumeric equality.
            return normalize(seen) == normalize(installedVersionName)
        }
        if (seenNums != instNums) return false
        // Sequence of numeric tokens is identical. Also require the alpha suffix on the
        // last numeric token to match (so 0.4.7a ≠ 0.4.7b). For all earlier tokens, the
        // numeric value alone is enough.
        val seenLastSuffix = lastNumericSuffix(seen)
        val instLastSuffix = lastNumericSuffix(installedVersionName)
        return seenLastSuffix == instLastSuffix
    }

    /** Split a string into the integer values of every digit-run it contains. */
    private fun numericTokens(s: String): List<Int> =
        numericTokenRegex.findAll(s).mapNotNull { it.value.toIntOrNull() }.toList()

    /** Extract the lowercase alpha suffix that follows the LAST numeric token, if any.
     *  "0.4.7b" -> "b" ; "1.2.3-pc" -> "" (the dash breaks it) ; "1.0a1" -> "a"
     *  (we treat "a1" as alpha-only by stopping at digits after letters). */
    private fun lastNumericSuffix(s: String): String {
        val lastDigit = s.indexOfLast { it.isDigit() }
        if (lastDigit < 0 || lastDigit == s.length - 1) return ""
        val tail = s.substring(lastDigit + 1)
        val suffix = StringBuilder()
        for (c in tail) {
            if (c.isLetter()) suffix.append(c.lowercaseChar()) else break
        }
        return suffix.toString()
    }

    private fun normalize(s: String): String = CatalogRepository.normalizeTitle(s)

    /**
     * Orders two version strings by their numeric components (missing trailing components count as
     * 0, so "1.21" == "1.21.000"). Returns >0 when [a] is newer than [b], <0 when older, 0 when the
     * same. When all numeric components are equal, a release (no alpha suffix on the last numeric
     * token) is considered newer than a pre-release suffix, otherwise suffixes compare alphabetically.
     * Used for "is the source version strictly newer than what's installed" checks.
     */
    fun compare(a: String?, b: String?): Int {
        val an = numericTokens(a.orEmpty())
        val bn = numericTokens(b.orEmpty())
        val size = maxOf(an.size, bn.size)
        for (i in 0 until size) {
            val av = an.getOrElse(i) { 0 }
            val bv = bn.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        val asuf = lastNumericSuffix(a.orEmpty())
        val bsuf = lastNumericSuffix(b.orEmpty())
        if (asuf == bsuf) return 0
        if (asuf.isEmpty()) return 1
        if (bsuf.isEmpty()) return -1
        return asuf.compareTo(bsuf)
    }

    /** Returns true if seen represents a different value than acknowledged. */
    fun differs(seen: String?, acknowledged: String?): Boolean {
        if (seen.isNullOrBlank()) return false
        if (acknowledged.isNullOrBlank()) return false
        return seen.trim() != acknowledged.trim()
    }
}
