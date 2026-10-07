package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigTest {
    @Test
    fun effectiveSupportUrlFallsBackForLegacyPlainThreadUrl() {
        val config = AppConfig(supportThreadUrl = "https://f95zone.to/threads/299985/")

        assertEquals(AppConfig.DEFAULT_SUPPORT_URL, config.effectiveSupportThreadUrl)
    }

    @Test
    fun effectiveSupportUrlFallsBackForLegacySlugThreadUrl() {
        val config = AppConfig(
            supportThreadUrl = "https://f95zone.to/threads/f95-updater-legacy-f95-only-tracker-v1-1-15-advancedappcreator.299985/"
        )

        assertEquals(AppConfig.DEFAULT_SUPPORT_URL, config.effectiveSupportThreadUrl)
    }

    @Test
    fun effectiveSupportUrlKeepsAgmThreadUrl() {
        val config = AppConfig(
            supportThreadUrl = "https://f95zone.to/threads/adult-game-manager-android-joiplay-game-update-tracker-v1-0-43-advancedappcreator.300548/"
        )

        assertEquals(config.supportThreadUrl, config.effectiveSupportThreadUrl)
    }
}
