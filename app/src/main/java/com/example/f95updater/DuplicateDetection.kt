package com.example.f95updater

/**
 * Pure duplicate / multi-version detection over the installed library. Two games are considered the
 * same title when they map to the same catalog entry (same F95 URL or catalog title) or, lacking a
 * mapping, when their names normalize equally after stripping version tokens. Free of Android/Compose
 * types for direct unit testing.
 */
internal data class DuplicateGroup(
    val key: String,
    val title: String,
    /** Members, newest first (by parsed version, then most-recent install/update time). */
    val rows: List<AppRow>,
) {
    val newest: AppRow get() = rows.first()
    val older: List<AppRow> get() = rows.drop(1)
}

private val VERSION_TOKEN = Regex("""[\s._-]v?\d+(?:\.\d+)*[a-z0-9]*$""", RegexOption.IGNORE_CASE)

private fun normalizeTitle(raw: String): String =
    raw.trim()
        .let { VERSION_TOKEN.replace(it, "") }
        .lowercase()
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()

/** Parse a version string into comparable numeric components; non-numeric parts are ignored. */
internal fun parseVersionKey(versionName: String?): List<Int> {
    if (versionName.isNullOrBlank()) return emptyList()
    return Regex("""\d+""").findAll(versionName).map { it.value.toIntOrNull() ?: 0 }.toList()
}

private fun compareVersionKeys(a: List<Int>, b: List<Int>): Int {
    val n = maxOf(a.size, b.size)
    for (i in 0 until n) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x.compareTo(y)
    }
    return 0
}

private fun catalogIdentity(row: AppRow): String? {
    val url = row.mapping?.f95Url?.trim()?.lowercase()?.ifBlank { null }
    if (url != null) return "url:$url"
    val title = row.mapping?.mappedCatalogTitle?.trim()?.lowercase()?.ifBlank { null }
    if (title != null) return "catalog:$title"
    return null
}

private fun recencyOf(app: InstalledApp): Long = maxOf(app.lastUpdateTime, app.firstInstallTime)

/**
 * Groups installed games that appear to be duplicates or multiple versions of the same title.
 * Only groups with two or more members are returned. Members are ordered newest-first so the first
 * element is the one to keep.
 */
internal fun findDuplicateGroups(rows: List<AppRow>): List<DuplicateGroup> {
    val buckets = LinkedHashMap<String, MutableList<AppRow>>()
    val titles = HashMap<String, String>()
    for (r in rows) {
        val catalog = catalogIdentity(r)
        val key = catalog ?: "title:" + normalizeTitle(effectiveGameName(r.installed, r.mapping))
        if (key.removePrefix("title:").isBlank()) continue
        buckets.getOrPut(key) { mutableListOf() }.add(r)
        titles.putIfAbsent(key, effectiveGameName(r.installed, r.mapping))
    }
    return buckets.entries
        .filter { it.value.size >= 2 }
        .map { (key, members) ->
            val sorted = members.sortedWith(
                Comparator<AppRow> { a, b ->
                    val vc = compareVersionKeys(
                        parseVersionKey(a.installed.versionName),
                        parseVersionKey(b.installed.versionName),
                    )
                    if (vc != 0) -vc else -recencyOf(a.installed).compareTo(recencyOf(b.installed))
                }
            )
            DuplicateGroup(key = key, title = titles[key] ?: sorted.first().installed.label, rows = sorted)
        }
        .sortedBy { it.title.lowercase() }
}
