package com.example.f95updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledAppIgnoreRulesTest {
    private fun app(
        packageName: String,
        label: String,
        source: AppSource = AppSource.Android,
    ) = InstalledApp(
        packageName = packageName,
        label = label,
        versionName = "",
        versionCode = 0,
        source = source,
    )

    @Test
    fun fileExplorerAndPatcherUtilitiesAreIgnored() {
        assertTrue(
            InstalledAppIgnoreRules.shouldIgnore(
                app("com.cxinventor.file.explorer", "Cx File Explorer"),
            ),
        )
        assertTrue(
            InstalledAppIgnoreRules.shouldIgnore(
                app("managed:reipatcher", "ReiPatcher", AppSource.Managed),
            ),
        )
    }

    @Test
    fun gameTitlesContainingGenericWordsRemainVisible() {
        assertFalse(
            InstalledAppIgnoreRules.shouldIgnore(
                app("game.example", "The File Manager's Daughter"),
            ),
        )
    }

    @Test
    fun existingMappingsAreNeverSuppressedByAutomaticIgnoreRules() {
        val utility = app("com.cxinventor.file.explorer", "Cx File Explorer")

        assertTrue(InstalledAppIgnoreRules.shouldSkipCatalogMatching(utility, hasExistingMapping = false))
        assertFalse(InstalledAppIgnoreRules.shouldSkipCatalogMatching(utility, hasExistingMapping = true))
    }
}
