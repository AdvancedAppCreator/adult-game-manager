package com.example.f95updater

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
