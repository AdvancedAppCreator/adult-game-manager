package com.example.f95updater

/**
 * Pure logic for bulk library actions (selection-mode delete / tag). Kept free of Android and
 * Compose types so it can be unit-tested directly.
 */

/** Partition of a bulk-delete selection by how the games are removed. */
internal data class BulkDeleteBreakdown(
    /** AGM-owned managed games; Winlator bindings may require a per-item runtime round-trip. */
    val managed: List<AppRow>,
    /** Folder-owned games AGM can delete silently in a batch (no per-item prompt). */
    val joiPlay: List<AppRow>,
    /** Winlator managed games — each removal is a per-item Winlator round-trip. */
    val winlator: List<AppRow>,
    /** Installed Android packages — each uninstall is a per-item system confirmation. */
    val android: List<AppRow>,
    /** Sum of effective installed sizes across the whole selection. */
    val totalBytes: Long,
    /** Kirikiroid folder games AGM can delete silently in a batch (no per-item prompt). */
    val kirikiroid: List<AppRow> = emptyList(),
) {
    val total: Int get() = managed.size + joiPlay.size + winlator.size + android.size + kirikiroid.size

    /** Items that require a per-item OS / Winlator confirmation (no silent native path exists). */
    val interactive: List<AppRow> get() = managed + winlator + android

    /** Folder-owned games AGM deletes silently up front (JoiPlay + Kirikiroid). */
    val silent: List<AppRow> get() = joiPlay + kirikiroid

    val silentCount: Int get() = joiPlay.size + kirikiroid.size
    val interactiveCount: Int get() = managed.size + winlator.size + android.size
}

internal fun buildBulkDeleteBreakdown(
    rows: List<AppRow>,
    sizeOf: (AppRow) -> Long,
): BulkDeleteBreakdown {
    val joi = ArrayList<AppRow>()
    val managed = ArrayList<AppRow>()
    val win = ArrayList<AppRow>()
    val andr = ArrayList<AppRow>()
    val krkr = ArrayList<AppRow>()
    var bytes = 0L
    for (r in rows) {
        when (r.installed.source) {
            AppSource.Managed -> managed.add(r)
            AppSource.JoiPlay -> joi.add(r)
            AppSource.Winlator -> win.add(r)
            AppSource.Android -> andr.add(r)
            AppSource.Kirikiroid -> krkr.add(r)
        }
        bytes += sizeOf(r).coerceAtLeast(0L)
    }
    return BulkDeleteBreakdown(managed, joi, win, andr, bytes, krkr)
}

/** Running tally of a bulk-delete run, spanning the silent and interactive phases. */
internal data class BulkTally(
    val total: Int,
    val processed: Int = 0,
    val failed: Int = 0,
) {
    val deleted: Int get() = (processed - failed).coerceAtLeast(0)
    fun advanced(failed: Boolean): BulkTally =
        copy(processed = processed + 1, failed = this.failed + if (failed) 1 else 0)
}

internal fun bulkDeleteSummary(tally: BulkTally): String =
    bulkDeleteSummary(tally.total, tally.deleted, tally.failed)

internal fun bulkDeleteSummary(total: Int, deleted: Int, failed: Int): String {
    val base = "Deleted $deleted of $total game${if (total == 1) "" else "s"}"
    return if (failed > 0) "$base ($failed failed / skipped)" else base
}
