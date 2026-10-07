package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun winlatorFeedFollowsFeedHostRelatively() {
        assertEquals(
            "https://downloads.example.test/app/winlator-secure/version.json",
            deriveRelatedVersionUrl(
                "https://downloads.example.test/app/adult-game-manager/version.json",
                AppUpdateTarget.WinlatorSecure.versionInfoRelativePath!!,
            ),
        )
    }

    @Test
    fun kirikiroidFeedFollowsFeedHostRelatively() {
        assertEquals(
            "https://downloads.example.test/app/kirikiroid/version.json",
            deriveRelatedVersionUrl(
                "https://downloads.example.test/app/adult-game-manager/version.json",
                AppUpdateTarget.Kirikiroid.versionInfoRelativePath!!,
            ),
        )
        assertEquals("com.agm.krkr2", AppUpdateTarget.Kirikiroid.packageName)
    }

    @Test
    fun winlatorFeedFollowsGithubAgmFeedRelatively() {
        assertEquals(
            "https://github.com/AdvancedAppCreator/adult-game-manager-releases/releases/download/winlator-secure/version.json",
            deriveRelatedVersionUrl(
                "https://github.com/AdvancedAppCreator/adult-game-manager-releases/releases/download/app/version.json",
                AppUpdateTarget.WinlatorSecure.versionInfoRelativePath!!,
            ),
        )
    }

    @Test
    fun winlatorFeedsAreDerivedFromEveryConfiguredAgmFeed() {
        val config = AppConfig(
            versionInfoUrl = "https://example.test/releases/app/version.json",
            versionInfoUrls = listOf(
                "https://example.test/releases/dev/version.json",
                "https://example.test/releases/app/version.json",
            ),
        )

        assertEquals(
            listOf(
                "https://example.test/releases/winlator-secure/version.json",
            ),
            AppUpdater().versionInfoUrls(config, AppUpdateTarget.WinlatorSecure),
        )
    }

    @Test
    fun releaseNotesAreUsedWhenNotesAreAbsent() {
        val info = AppUpdateInfo(
            versionCode = 110117,
            versionName = "11.1-secure.17",
            apkUrl = "https://example.test/winlator.apk",
            releaseNotes = "Winlator changes",
        )

        assertEquals("Winlator changes", info.effectiveNotes)
        assertEquals("Preferred notes", info.copy(notes = "Preferred notes").effectiveNotes)
    }

    @Test
    fun optionalInstallIsOfferedAgainOnlyForANewerVersion() {
        assertTrue(shouldOfferOptionalInstall(null, 110117))
        assertFalse(shouldOfferOptionalInstall(110117, 110117))
        assertFalse(shouldOfferOptionalInstall(110118, 110117))
        assertTrue(shouldOfferOptionalInstall(110117, 110118))
    }

    @Test
    fun apkUrlOverrideClearsFeedChecksumForTheReplacedArtifact() {
        val info = AppUpdateInfo(
            versionCode = 200,
            versionName = "2.0",
            apkUrl = "https://example.test/feed.apk",
            sha256 = "FEED_CHECKSUM",
        )

        assertEquals(info, applyApkUrlOverride(info, null))
        assertEquals(
            info.copy(apkUrl = "https://example.test/override.apk", sha256 = ""),
            applyApkUrlOverride(info, " https://example.test/override.apk "),
        )
    }

    @Test
    fun sha256VerificationAcceptsMatchAndDeletesMismatch() {
        val matching = File.createTempFile("agm-update", ".apk").apply { writeText("hello") }
        verifySha256(
            matching,
            "2CF24DBA5FB0A30E26E83B2AC5B9E29E1B161E5C1FA7425E73043362938B9824",
        )
        assertTrue(matching.exists())
        matching.delete()

        val mismatching = File.createTempFile("agm-update", ".apk").apply { writeText("hello") }
        val failure = runCatching { verifySha256(mismatching, "00") }
        assertTrue(failure.isFailure)
        assertFalse(mismatching.exists())
    }
}
