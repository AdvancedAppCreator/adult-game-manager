package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Test

class BulkActionsTest {

    private fun app(pkg: String, source: AppSource, size: Long = 0L): AppRow =
        AppRow(
            installed = InstalledApp(
                packageName = pkg,
                label = pkg,
                versionName = "1.0",
                versionCode = 1L,
                apkSize = size,
                source = source,
            ),
            mapping = null,
            status = UpdateStatus.Unknown,
        )

    @Test
    fun breakdown_partitionsBySource_andSumsSizes() {
        val rows = listOf(
            app("a", AppSource.JoiPlay, 100),
            app("b", AppSource.Winlator, 200),
            app("c", AppSource.Android, 300),
            app("d", AppSource.JoiPlay, 50),
        )
        val b = buildBulkDeleteBreakdown(rows) { it.installed.apkSize }
        assertEquals(2, b.joiPlay.size)
        assertEquals(1, b.winlator.size)
        assertEquals(1, b.android.size)
        assertEquals(4, b.total)
        assertEquals(2, b.silentCount)
        assertEquals(2, b.interactiveCount)
        assertEquals(listOf("b", "c"), b.interactive.map { it.installed.packageName })
        assertEquals(650L, b.totalBytes)
    }

    @Test
    fun breakdown_kirikiroidIsSilentlyDeleted() {
        val rows = listOf(
            app("a", AppSource.JoiPlay, 100),
            app("k", AppSource.Kirikiroid, 400),
            app("w", AppSource.Winlator, 200),
        )
        val b = buildBulkDeleteBreakdown(rows) { it.installed.apkSize }
        assertEquals(1, b.kirikiroid.size)
        assertEquals(3, b.total)
        // JoiPlay + Kirikiroid are the silent (no per-item prompt) folder deletes.
        assertEquals(2, b.silentCount)
        assertEquals(listOf("a", "k"), b.silent.map { it.installed.packageName })
        assertEquals(1, b.interactiveCount)
        assertEquals(listOf("w"), b.interactive.map { it.installed.packageName })
        assertEquals(700L, b.totalBytes)
    }

    @Test
    fun tally_advanceTracksProcessedAndFailures() {
        var t = BulkTally(total = 3)
        t = t.advanced(false)
        t = t.advanced(true)
        t = t.advanced(false)
        assertEquals(3, t.processed)
        assertEquals(1, t.failed)
        assertEquals(2, t.deleted)
    }

    @Test
    fun summary_formatsWithAndWithoutFailures() {
        assertEquals("Deleted 3 of 3 games", bulkDeleteSummary(BulkTally(3, 3, 0)))
        assertEquals(
            "Deleted 2 of 3 games (1 failed / skipped)",
            bulkDeleteSummary(BulkTally(3, 3, 1)),
        )
        assertEquals("Deleted 1 of 1 game", bulkDeleteSummary(1, 1, 0))
    }
}
