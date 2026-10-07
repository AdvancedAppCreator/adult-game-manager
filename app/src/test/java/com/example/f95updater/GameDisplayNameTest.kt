package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Test

class GameDisplayNameTest {
    private val app = InstalledApp(
        packageName = "pkg.test",
        label = "Detected Name",
        versionName = "",
        versionCode = 1,
    )

    @Test
    fun associatedCatalogTitleIsDefaultName() {
        val mapping = AppMapping(
            packageName = app.packageName,
            mappedCatalogTitle = "Catalog Name",
        )

        assertEquals("Catalog Name", effectiveGameName(app, mapping))
        assertEquals("Translated Name", effectiveGameName(app, mapping, "Translated Name"))
    }

    @Test
    fun displayNameOverrideDoesNotReplaceAssociation() {
        val mapping = AppMapping(
            packageName = app.packageName,
            displayNameOverride = "My Name",
            mappedCatalogId = 123,
            mappedCatalogSource = SOURCE_F95ZONE,
            mappedCatalogTitle = "Catalog Name",
        )

        assertEquals("My Name", effectiveGameName(app, mapping))
        assertEquals("My Name", effectiveGameName(app, mapping, "Translated Name"))
        assertEquals(123, mapping.mappedCatalogId)
        assertEquals("Catalog Name", mapping.mappedCatalogTitle)
    }

    @Test
    fun detectedNameIsFallbackWithoutAssociation() {
        assertEquals("Detected Name", effectiveGameName(app, null))
    }

    @Test
    fun matchingDefaultNameClearsOverride() {
        assertEquals("", displayNameOverrideFor("Catalog Name", "Catalog Name"))
        assertEquals("", displayNameOverrideFor("   ", "Catalog Name"))
        assertEquals("Custom", displayNameOverrideFor(" Custom ", "Catalog Name"))
    }

    @Test
    fun librarySearchMatchesOverrideAssociationAndDetectedNames() {
        val mapping = AppMapping(
            packageName = app.packageName,
            mappedCatalogTitle = "Catalog Name",
            displayNameOverride = "My Name",
        )

        assertEquals(true, matchesLibrarySearchText(app, mapping, "Catalog Name", "my name"))
        assertEquals(true, matchesLibrarySearchText(app, mapping, "Catalog Name", "catalog"))
        assertEquals(true, matchesLibrarySearchText(app, mapping, "Catalog Name", "detected"))
    }

    @Test
    fun librarySearchMatchesFolderPathSubstrings() {
        val managed = app.copy(
            source = AppSource.Managed,
            storagePath = "/storage/emulated/0/Download/Kimochi/Peasants Quest",
            storageFolderName = "Peasants Quest",
        )

        assertEquals(true, matchesLibrarySearchText(managed, null, null, "kimochi"))
        assertEquals(true, matchesLibrarySearchText(managed, null, null, "peasants"))
        assertEquals(false, matchesLibrarySearchText(managed, null, null, "documents"))
    }

    @Test
    fun libraryNameSortUsesDisplayedName() {
        val mapping = AppMapping(
            packageName = app.packageName,
            mappedCatalogTitle = "Catalog Name",
            displayNameOverride = "Zed",
        )

        assertEquals("zed", libraryNameSortKey(app, mapping, "Catalog Name"))
    }
}
