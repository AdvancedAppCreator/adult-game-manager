package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallRoutingTest {
    @Test
    fun joiPlayArchivePicksAlwaysInspectForUpgradeBeforeExtraction() {
        listOf("game.zip", "GAME.RAR", "release.7z", "zip").forEach { name ->
            assertEquals(
                name,
                InstallRouting.JoiPlayPickRoute.InspectArchiveForUpgrade,
                InstallRouting.routeJoiPlayPick(name),
            )
        }
    }

    @Test
    fun joiPlayLaunchFilesRouteDirectlyToJoiPlayImporter() {
        listOf("Game.exe", "start.sh", "main.py", "index.html", "movie.swf", "project.jgp").forEach { name ->
            assertEquals(
                name,
                InstallRouting.JoiPlayPickRoute.LaunchFile,
                InstallRouting.routeJoiPlayPick(name),
            )
        }
    }

    @Test
    fun apkArchivePicksUseApkExtractionNotJoiPlayUpgradeInspection() {
        listOf("payload.zip", "payload.rar", "payload.7z").forEach { name ->
            assertEquals(
                name,
                InstallRouting.ApkPickRoute.ExtractArchiveForApk,
                InstallRouting.routeApkPick(name),
            )
        }
    }

    @Test
    fun apkFilesRouteToDirectApkInstaller() {
        assertEquals(InstallRouting.ApkPickRoute.InstallApk, InstallRouting.routeApkPick("AdultGameManager.apk"))
    }

    @Test
    fun upgradeInspectionOnlyFallsThroughWhenNoMatchesExist() {
        val match = InstalledApp(
            packageName = "joiplay.test",
            label = "Test JoiPlay Game",
            versionName = "",
            versionCode = 0,
            source = AppSource.JoiPlay,
            storagePath = "C:\\Games\\Test",
            joiPlayExecFile = "Game.exe",
        )

        assertEquals(
            InstallRouting.UpgradeInspectionRoute.ShowUpgradePrompt,
            InstallRouting.routeUpgradeInspection(listOf(match)),
        )
        assertEquals(
            InstallRouting.UpgradeInspectionRoute.ExtractAsNewInstall,
            InstallRouting.routeUpgradeInspection(emptyList()),
        )
    }

    @Test
    fun pickerExtensionSetsStayInSyncWithRoutes() {
        assertTrue("JoiPlay picker must expose zip", "zip" in InstallRouting.joiPlayPickerExtensions)
        assertTrue("JoiPlay picker must expose rar", "rar" in InstallRouting.joiPlayPickerExtensions)
        assertTrue("JoiPlay picker must expose 7z", "7z" in InstallRouting.joiPlayPickerExtensions)
        assertTrue("APK picker must expose archives", InstallRouting.apkPickerExtensions.containsAll(setOf("zip", "rar", "7z")))
    }
}
