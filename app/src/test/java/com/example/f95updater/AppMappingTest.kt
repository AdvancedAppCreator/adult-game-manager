package com.example.f95updater

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
                CatalogInstallKey(CatalogSource.F95Zone, "12345"),
            ),
        )
    }

    @Test
    fun catalogInstallKeysIncludeSourceAwareMapping() {
        val mapping = AppMapping(
            packageName = "pkg.test",
            mappedCatalogSource = CatalogSource.AdultGameWorld,
            mappedCatalogSourceId = "agw-example",
            mappedCatalogUrl = "https://adultgamesworld.com/agw-example/",
        )

        assertTrue(
            catalogInstallKeys(mapping).contains(
                CatalogInstallKey(CatalogSource.AdultGameWorld, "agw-example"),
            ),
        )
        assertTrue(catalogInstallUrls(mapping).contains("https://adultgamesworld.com/agw-example/"))
    }
}
