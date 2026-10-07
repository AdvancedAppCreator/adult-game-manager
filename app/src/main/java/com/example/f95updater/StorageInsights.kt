package com.example.f95updater

/**
 * Pure aggregation powering the storage dashboard and the stale-game surfacing. Free of Android and
 * Compose types so it can be unit-tested directly.
 */

internal data class SourceUsage(
    val source: AppSource,
    val count: Int,
    val bytes: Long,
)

internal data class StorageInsights(
    /** Per-source totals, largest first. */
    val perSource: List<SourceUsage>,
    val totalBytes: Long,
    val totalCount: Int,
    /** Biggest games by effective installed size, largest first. */
    val biggest: List<SizedRow>,
    /** Games not opened within the stale window (or never opened), oldest first. */
    val stale: List<SizedRow>,
    /** How the stale window was defined, for display. */
    val staleThresholdMs: Long,
) {
    data class SizedRow(val row: AppRow, val bytes: Long, val lastUsed: Long)
}

/** One month, used as the default granularity for the stale window. */
internal const val STALE_MONTH_MS: Long = 30L * 24 * 60 * 60 * 1000

internal fun buildStorageInsights(
    rows: List<AppRow>,
    sizeOf: (AppRow) -> Long,
    lastUsedOf: (AppRow) -> Long,
    now: Long,
    staleThresholdMs: Long,
    topN: Int = 10,
): StorageInsights {
    val bySource = LinkedHashMap<AppSource, Pair<Int, Long>>()
    var totalBytes = 0L
    val sized = ArrayList<StorageInsights.SizedRow>(rows.size)
    for (r in rows) {
        val bytes = sizeOf(r).coerceAtLeast(0L)
        val used = lastUsedOf(r)
        sized.add(StorageInsights.SizedRow(r, bytes, used))
        totalBytes += bytes
        val (c, b) = bySource[r.installed.source] ?: (0 to 0L)
        bySource[r.installed.source] = (c + 1) to (b + bytes)
    }
    val perSource = bySource.entries
        .map { SourceUsage(it.key, it.value.first, it.value.second) }
        .sortedByDescending { it.bytes }

    val biggest = sized.sortedByDescending { it.bytes }.take(topN)

    val staleCutoff = now - staleThresholdMs
    val stale = sized
        .filter { it.lastUsed <= 0L || it.lastUsed < staleCutoff }
        // Never-opened first, then oldest-used first.
        .sortedWith(compareByDescending<StorageInsights.SizedRow> { it.lastUsed <= 0L }.thenBy { it.lastUsed })

    return StorageInsights(
        perSource = perSource,
        totalBytes = totalBytes,
        totalCount = rows.size,
        biggest = biggest,
        stale = stale,
        staleThresholdMs = staleThresholdMs,
    )
}
