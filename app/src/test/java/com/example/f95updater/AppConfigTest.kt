package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AppConfigTest {
    @Test
    fun bundledPublicConfigContainsContactAndDonationDestinations() {
        val text = File("src/main/assets/app_config.json").readText()
        val config = AppConfigStore.decodeConfig(text)

        assertEquals("advancedappcreator@proton.me", config.contactEmail)
        assertEquals("https://buymeacoffee.com/advancedappcreator", config.donationUrl)
        assertEquals("https://buy.stripe.com/14A7sM5zQgL0eGkbz02ZO00", config.stripeDonationUrl)
    }

    @Test
    fun partialOverrideRetainsBundledPublicContactAndDonationValues() {
        val baseline = AppConfigStore.decodeConfig(
            File("src/main/assets/app_config.json").readText(),
        )

        val config = AppConfigStore.decodeConfig(
            """{"diagnosticsEnabled":true}""",
            baseline,
        )

        assertEquals(true, config.diagnosticsEnabled)
        assertEquals(baseline.contactEmail, config.contactEmail)
        assertEquals(baseline.donationUrl, config.donationUrl)
        assertEquals(baseline.stripeDonationUrl, config.stripeDonationUrl)
    }

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
