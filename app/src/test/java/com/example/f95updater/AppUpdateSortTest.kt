package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Test

class AppUpdateSortTest {
    @Test
    fun appUpdateSortUsesLastUpdateTime() {
        val older = InstalledApp(
            packageName = "older",
            label = "Older",
            versionName = "",
            versionCode = 1,
            lastUpdateTime = 100L,
        )
        val newer = InstalledApp(
            packageName = "newer",
            label = "Newer",
            versionName = "",
            versionCode = 1,
            lastUpdateTime = 200L,
        )

        assertEquals(
            listOf("older", "newer"),
            listOf(newer, older).sortedBy(::appUpdatedAt).map { it.packageName },
        )
    }

    @Test
    fun appUpdateSortHasRequestedLabel() {
        assertEquals("App update", SortKey.AppUpdated.label)
    }
}
