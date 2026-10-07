package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateDetectionTest {

    private fun row(
        pkg: String,
        label: String,
        version: String,
        source: AppSource = AppSource.JoiPlay,
        f95Url: String? = null,
        updated: Long = 0L,
    ): AppRow = AppRow(
        installed = InstalledApp(
            packageName = pkg, label = label, versionName = version, versionCode = 1,
            source = source, lastUpdateTime = updated,
        ),
        mapping = f95Url?.let { AppMapping(packageName = pkg, f95Url = it) },
        status = UpdateStatus.Unknown,
    )

    @Test
    fun versionParsing() {
        assertEquals(listOf(1, 2, 3), parseVersionKey("v1.2.3"))
        assertEquals(listOf(0, 5), parseVersionKey("0.5b"))
        assertTrue(parseVersionKey(null).isEmpty())
    }

    @Test
    fun multiVersionSameTitleGrouped_newestFirst() {
        val rows = listOf(
            row("a", "Peasant's Quest v1.0", "1.0"),
            row("b", "Peasant's Quest v1.2", "1.2"),
            row("c", "Some Other Game", "1.0"),
        )
        val groups = findDuplicateGroups(rows)
        assertEquals(1, groups.size)
        assertEquals(listOf("b", "a"), groups[0].rows.map { it.installed.packageName })
        assertEquals(listOf("a"), groups[0].older.map { it.installed.packageName })
    }

    @Test
    fun sameCatalogAcrossSourcesGrouped() {
        val rows = listOf(
            row("joiplay:x", "X", "1.0", AppSource.JoiPlay, f95Url = "https://f95zone.to/threads/x.1/"),
            row("winlator:x", "X port", "1.0", AppSource.Winlator, f95Url = "https://F95ZONE.to/threads/x.1/"),
            row("y", "Unrelated", "1.0"),
        )
        val groups = findDuplicateGroups(rows)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].rows.size)
    }

    @Test
    fun noFalsePositives() {
        val rows = listOf(
            row("a", "Alpha", "1.0"),
            row("b", "Beta", "1.0"),
        )
        assertTrue(findDuplicateGroups(rows).isEmpty())
    }
}
