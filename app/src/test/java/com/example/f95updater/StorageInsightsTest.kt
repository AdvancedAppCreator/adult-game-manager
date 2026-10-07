package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageInsightsTest {

    private fun row(pkg: String, source: AppSource): AppRow =
        AppRow(
            installed = InstalledApp(packageName = pkg, label = pkg, versionName = "1", versionCode = 1, source = source),
            mapping = null,
            status = UpdateStatus.Unknown,
        )

    @Test
    fun perSourceTotalsAndBiggest() {
        val rows = listOf(
            row("a", AppSource.JoiPlay),
            row("b", AppSource.JoiPlay),
            row("c", AppSource.Winlator),
        )
        val sizes = mapOf("a" to 100L, "b" to 300L, "c" to 50L)
        val insights = buildStorageInsights(
            rows = rows,
            sizeOf = { sizes[it.installed.packageName] ?: 0L },
            lastUsedOf = { 0L },
            now = 1_000_000L,
            staleThresholdMs = 10L,
            topN = 2,
        )
        assertEquals(450L, insights.totalBytes)
        assertEquals(3, insights.totalCount)
        // JoiPlay (400) before Winlator (50)
        assertEquals(AppSource.JoiPlay, insights.perSource[0].source)
        assertEquals(400L, insights.perSource[0].bytes)
        assertEquals(2, insights.perSource[0].count)
        // biggest capped at topN=2, descending
        assertEquals(listOf("b", "a"), insights.biggest.map { it.row.installed.packageName })
    }

    @Test
    fun staleIncludesNeverAndOld_neverFirst() {
        val now = 10_000_000L
        val threshold = 1_000L
        val rows = listOf(
            row("never", AppSource.JoiPlay),
            row("recent", AppSource.JoiPlay),
            row("old", AppSource.JoiPlay),
        )
        val used = mapOf(
            "never" to 0L,
            "recent" to now - 10L,        // within window -> not stale
            "old" to now - 5_000L,        // older than window -> stale
        )
        val insights = buildStorageInsights(
            rows = rows,
            sizeOf = { 0L },
            lastUsedOf = { used[it.installed.packageName] ?: 0L },
            now = now,
            staleThresholdMs = threshold,
        )
        val stalePkgs = insights.stale.map { it.row.installed.packageName }
        assertTrue("recent must not be stale", "recent" !in stalePkgs)
        assertEquals(listOf("never", "old"), stalePkgs)
    }
}
