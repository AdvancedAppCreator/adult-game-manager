package com.example.f95updater

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun legacyMappingJsonDefaultsPersonalFields() {
        val mapping = json.decodeFromString(
            AppMapping.serializer(),
            """{"packageName":"pkg.test","f95Url":"https://f95zone.to/threads/1/"}""",
        )

        assertEquals(UserGameStatus.None, mapping.userStatus)
        assertNull(mapping.personalRating)
        assertEquals("", mapping.personalNotes)
    }

    @Test
    fun personalFieldsRoundTrip() {
        val original = AppMapping(
            packageName = "pkg.test",
            f95Url = "https://f95zone.to/threads/1/",
            userStatus = UserGameStatus.Playing,
            personalRating = 4,
            personalNotes = "Route B next.",
        )

        val decoded = json.decodeFromString(AppMapping.serializer(), json.encodeToString(AppMapping.serializer(), original))

        assertEquals(UserGameStatus.Playing, decoded.userStatus)
        assertEquals(4, decoded.personalRating)
        assertEquals("Route B next.", decoded.personalNotes)
    }

    @Test
    fun catalogInstallKeysIncludeF95ThreadMapping() {
        val mapping = AppMapping(
            packageName = "pkg.test",
            f95Url = "https://f95zone.to/threads/example-game.12345/",
        )

        assertTrue(
            catalogInstallKeys(mapping).contains(
                CatalogInstallKey(SOURCE_F95ZONE, "12345"),
            ),
        )
    }

    @Test
    fun catalogInstallKeysIncludeSourceAwareMapping() {
        val mapping = AppMapping(
            packageName = "pkg.test",
            mappedCatalogSource = SOURCE_ADULTGAMEWORLD,
            mappedCatalogSourceId = "agw-example",
            mappedCatalogUrl = "https://adultgamesworld.com/agw-example/",
        )

        assertTrue(
            catalogInstallKeys(mapping).contains(
                CatalogInstallKey(SOURCE_ADULTGAMEWORLD, "agw-example"),
            ),
        )
        assertTrue(catalogInstallUrls(mapping).contains("https://adultgamesworld.com/agw-example/"))
    }

    @Test
    fun installedBadgeOnlyFromLiveScannedGames() {
        val mappings = mapOf(
            "pkg.live" to AppMapping(packageName = "pkg.live", f95Url = "https://f95zone.to/threads/g.111/"),
            "pkg.gone" to AppMapping(packageName = "pkg.gone", f95Url = "https://f95zone.to/threads/g.222/"),
        )

        val identities = installedCatalogIdentities(mappings, setOf("pkg.live"))

        assertTrue(identities.keys.contains(CatalogInstallKey(SOURCE_F95ZONE, "111")))
        // The mapping whose game is not in the live scan must not light the badge.
        assertFalse(identities.keys.contains(CatalogInstallKey(SOURCE_F95ZONE, "222")))
    }

    @Test
    fun pruneRemovesEmptyStaleMappingButKeepsLive() {
        val mappings = mapOf(
            "pkg.live" to AppMapping(packageName = "pkg.live", f95Url = "https://f95zone.to/threads/g.1/"),
            "pkg.gone" to AppMapping(packageName = "pkg.gone", f95Url = "https://f95zone.to/threads/g.2/"),
        )

        val plan = computeStalePrune(
            mappings,
            installedPackageNames = setOf("pkg.live"),
            androidScanned = true,
            joiplayScanned = true,
        )

        assertEquals(setOf("pkg.gone"), plan.removeKeys)
        assertEquals(0, plan.retainedWithData)
    }

    @Test
    fun pruneKeepsStaleMappingCarryingUserData() {
        val mappings = mapOf(
            "pkg.rated" to AppMapping(packageName = "pkg.rated", personalRating = 5),
            "pkg.noted" to AppMapping(packageName = "pkg.noted", personalNotes = "great"),
            "pkg.status" to AppMapping(packageName = "pkg.status", userStatus = UserGameStatus.Completed),
            "pkg.empty" to AppMapping(packageName = "pkg.empty"),
        )

        val plan = computeStalePrune(
            mappings,
            installedPackageNames = emptySet(),
            androidScanned = true,
            joiplayScanned = true,
        )

        assertEquals(setOf("pkg.empty"), plan.removeKeys)
        assertEquals(3, plan.retainedWithData)
    }

    @Test
    fun pruneSkipsKindThatDidNotScan() {
        val mappings = mapOf(
            "joiplay:GameA" to AppMapping(packageName = "joiplay:GameA"),
            "com.android.game" to AppMapping(packageName = "com.android.game"),
        )

        // JoiPlay source produced nothing this scan (e.g. backup not loaded) -> its
        // absent mappings must NOT be treated as uninstalled. Android did scan.
        val plan = computeStalePrune(
            mappings,
            installedPackageNames = emptySet(),
            androidScanned = true,
            joiplayScanned = false,
        )

        assertEquals(setOf("com.android.game"), plan.removeKeys)
    }
}
