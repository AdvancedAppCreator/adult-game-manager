package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogNavigationTest {
    private val group = CatalogRowsGroup(
        agmGroupId = "g_exact",
        members = listOf(
            SourceCatalogEntry(
                source = "otomi",
                sourceId = "42",
                canonicalUrl = "https://otomi-games.com/post/42",
                title = "Exact Game",
                agmGroupId = "g_exact",
            ),
            SourceCatalogEntry(
                source = SOURCE_F95ZONE,
                sourceId = "123",
                canonicalUrl = "https://f95zone.to/threads/123/",
                title = "Exact Game",
                agmGroupId = "g_exact",
            ),
        ),
    )

    @Test
    fun installedNavigationRequiresAConfirmedIdentityNotATitleGuess() {
        assertNull(
            confirmedCatalogNavigationIdentity(
                AppMapping(packageName = "pkg", mappedCatalogTitle = "Exact Game"),
            ),
        )
        assertEquals(
            "g_exact",
            confirmedCatalogNavigationIdentity(
                AppMapping(packageName = "pkg", mappedAgmGroupId = "g_exact"),
            )?.agmGroupId,
        )
    }

    @Test
    fun downloadNavigationUsesExactCatalogIdentity() {
        assertEquals(
            CatalogNavigationIdentity(
                agmGroupId = "g_exact",
                source = "otomi",
                sourceId = "42",
                canonicalUrl = "https://otomi-games.com/post/42",
            ),
            catalogNavigationIdentity(
                CatalogGame(
                    thread_id = 0,
                    source = "otomi",
                    sourceId = "42",
                    sourceUrl = "https://otomi-games.com/post/42",
                    agmGroupId = "g_exact",
                ),
            ),
        )
    }

    @Test
    fun catalogToInstalledUsesGroupThenExactLegacySourceFallback() {
        val mappings = mapOf(
            "pkg.group" to AppMapping(packageName = "pkg.group", mappedAgmGroupId = "g_exact"),
            "pkg.legacy" to AppMapping(
                packageName = "pkg.legacy",
                mappedCatalogSource = "otomi",
                mappedCatalogSourceId = "42",
            ),
        )

        assertEquals(
            "pkg.group",
            installedPackageForCatalogGroup(group, mappings, setOf("pkg.group", "pkg.legacy")),
        )
        assertEquals(
            "pkg.legacy",
            installedPackageForCatalogGroup(group, mappings, setOf("pkg.legacy")),
        )
    }

    @Test
    fun titleOnlyAndUninstalledMappingsNeverExposeCatalogNavigation() {
        val mappings = mapOf(
            "pkg.guess" to AppMapping(packageName = "pkg.guess", mappedCatalogTitle = "Exact Game"),
            "pkg.stale" to AppMapping(packageName = "pkg.stale", mappedAgmGroupId = "g_exact"),
        )

        assertNull(installedPackageForCatalogGroup(group, mappings, setOf("pkg.guess")))
        assertNull(installedPackageForCatalogGroup(group, mappings, emptySet()))
    }

    @Test
    fun notInCatalogMappingRejectsStaleNavigationIdentity() {
        val mapping = AppMapping(
            packageName = "pkg",
            mappedAgmGroupId = "g_exact",
            notOnF95 = true,
        )

        assertNull(confirmedCatalogNavigationIdentity(mapping))
        assertNull(
            installedPackageForCatalogGroup(
                group,
                mapOf(mapping.packageName to mapping),
                setOf(mapping.packageName),
            ),
        )
    }

    @Test
    fun installedNavigationRevealIsRemovedAfterRequestConsumption() {
        val destination = AppRow(
            installed = InstalledApp("pkg.target", "Target", versionName = "", versionCode = 1),
            mapping = null,
            status = UpdateStatus.NotMapped,
        )
        val filtered = listOf(
            AppRow(
                installed = InstalledApp("pkg.visible", "Visible", versionName = "", versionCode = 1),
                mapping = null,
                status = UpdateStatus.NotMapped,
            ),
        )

        assertEquals(
            listOf("pkg.target", "pkg.visible"),
            revealInstalledNavigationDestination(filtered, destination).map { it.installed.packageName },
        )
        assertEquals(
            listOf("pkg.visible"),
            revealInstalledNavigationDestination(filtered, null).map { it.installed.packageName },
        )
    }
}
