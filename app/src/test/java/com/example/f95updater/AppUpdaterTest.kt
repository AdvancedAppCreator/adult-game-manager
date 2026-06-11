package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun effectiveVersionInfoUrlsCombinesPrimaryAndAdditionalFeeds() {
        val config = AppConfig(
            versionInfoUrl = " https://example.test/public/version.json ",
            versionInfoUrls = listOf(
                "",
                "https://example.test/dev/version.json",
                "https://example.test/public/version.json",
            ),
        )

        assertEquals(
            listOf(
                "https://example.test/public/version.json",
                "https://example.test/dev/version.json",
            ),
            config.effectiveVersionInfoUrls,
        )
    }

    @Test
    fun newestVersionInfoChoosesHighestVersionCode() {
        val older = AppUpdateInfo(versionCode = 195, versionName = "1.1.10", apkUrl = "https://example.test/public.apk")
        val newer = AppUpdateInfo(versionCode = 196, versionName = "1.1.11", apkUrl = "https://example.test/dev.apk")

        assertEquals(newer, AppUpdater().newestVersionInfo(listOf(older, newer)))
        assertEquals(newer, AppUpdater().newestVersionInfo(listOf(newer, older)))
    }
}
