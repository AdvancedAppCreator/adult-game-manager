package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallRoutingTest {
    @Test
    fun directFilesRouteByValidatedType() {
        assertEquals(
            InstallRouting.PickRoute.InstallApk,
            InstallRouting.routePick("game.apk"),
        )
        assertEquals(
            InstallRouting.PickRoute.ChooseRunner,
            InstallRouting.routePick("Game.exe"),
        )
        listOf("start.sh", "main.py", "movie.swf", "project.jgp")
            .forEach { name ->
                assertEquals(
                    name,
                    InstallRouting.PickRoute.LaunchJoiPlayFile,
                    InstallRouting.routePick(name),
                )
            }
        listOf("index.html", "page.htm").forEach { name ->
            assertEquals(
                name,
                InstallRouting.PickRoute.HtmlOnly,
                InstallRouting.routePick(name),
            )
        }
        listOf("game.zip", "GAME.RAR", "release.7z").forEach { name ->
            assertEquals(
                name,
                InstallRouting.PickRoute.InspectArchive,
                InstallRouting.routePick(name),
            )
        }
    }

    @Test
    fun splitBundleExtensionsAreRejectedBeforeExtraction() {
        listOf("game.xapk", "game.apks", "game.apkm").forEach { name ->
            val route = InstallRouting.routePick(name)
            assertTrue(route is InstallRouting.PickRoute.Unsupported)
            assertEquals(
                InstallRouting.splitBundleMessage,
                (route as InstallRouting.PickRoute.Unsupported).message,
            )
        }
    }

    @Test
    fun archiveWithOneApkRoutesToAndroidEvenWhenItAlsoContainsWindowsFiles() {
        assertEquals(
            InstallRouting.ArchiveRoute.Extract(InstallRouting.Target.Android),
            InstallRouting.routeArchive(
                listOf("payload/game.apk", "payload/Game.exe", "payload/start.py")
            ),
        )
    }

    @Test
    fun archiveSplitAndMultiApkBundlesAreRejected() {
        listOf(
            listOf("base.apk", "split_config.arm64_v8a.apk"),
            listOf("base.apk", "config.en.apk"),
            listOf("game-one.apk", "game-two.apk"),
            listOf("split_feature.apk"),
        ).forEach { entries ->
            assertEquals(
                entries.toString(),
                InstallRouting.ArchiveRoute.Unsupported(InstallRouting.splitBundleMessage),
                InstallRouting.routeArchive(entries),
            )
        }
    }

    @Test
    fun winlatorExeArchivesDeferToRunnerChoice() {
        assertEquals(
            InstallRouting.ArchiveRoute.ChooseRunner,
            InstallRouting.routeArchive(listOf("Game.exe", "script.py", "index.html")),
        )
    }

    @Test
    fun scriptOnlyArchivesRouteToJoiPlay() {
        assertEquals(
            InstallRouting.ArchiveRoute.Extract(InstallRouting.Target.JoiPlay),
            InstallRouting.routeArchive(listOf("game/script.rpy", "game/start.py")),
        )
    }

    @Test
    fun xp3OnlyArchiveDefersToRunnerChoice() {
        assertEquals(
            InstallRouting.ArchiveRoute.ChooseRunner,
            InstallRouting.routeArchive(listOf("MyGame/data.xp3", "MyGame/readme.txt")),
        )
    }

    @Test
    fun wrapperWithOneNestedArchiveRoutesForRecursiveExtraction() {
        assertEquals(
            InstallRouting.ArchiveRoute.ExtractNested(
                "otomi-games.com_1EI0HP0M/RJ01261991.zip",
            ),
            InstallRouting.routeArchive(
                listOf(
                    "otomi-games.com_1EI0HP0M/OTOMI-GAMES.COM.url",
                    "otomi-games.com_1EI0HP0M/RJ01261991.zip",
                ),
            ),
        )
    }

    @Test
    fun wrappersWithMultipleNestedArchivesAreRejected() {
        val route = InstallRouting.routeArchive(listOf("part-one.zip", "part-two.rar"))
        assertTrue(route is InstallRouting.ArchiveRoute.Unsupported)
    }

    @Test
    fun contentWithoutGameFilesRoutesToOtherBucket() {
        assertEquals(
            InstallRouting.ArchiveRoute.Other,
            InstallRouting.routeArchive(listOf("readme.txt", "cover.png")),
        )
    }

    @Test
    fun emptyArchivesAreRejected() {
        val route = InstallRouting.routeArchive(listOf("folder/", ""))
        assertTrue(route is InstallRouting.ArchiveRoute.Unsupported)
    }

    @Test
    fun videoCollectionsRouteToVideosBucket() {
        assertEquals(
            InstallRouting.ArchiveRoute.Videos,
            InstallRouting.routeArchive(listOf("Scene 1.mp4", "Scene 2.mkv", "cover.jpg")),
        )
    }

    @Test
    fun videosWinOverBareHtml() {
        assertEquals(
            InstallRouting.ArchiveRoute.Videos,
            InstallRouting.routeArchive(listOf("index.html", "clip.mp4")),
        )
    }

    @Test
    fun bareHtmlOnlyArchivesPromptForJoiPlay() {
        assertEquals(
            InstallRouting.ArchiveRoute.HtmlOnly,
            InstallRouting.routeArchive(listOf("index.html", "assets/style.css")),
        )
    }

    @Test
    fun scriptGamesWinOverVideos() {
        assertEquals(
            InstallRouting.ArchiveRoute.Extract(InstallRouting.Target.JoiPlay),
            InstallRouting.routeArchive(listOf("game/start.py", "intro.mp4")),
        )
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
    fun pickerExtensionsCoverEverySupportedInput() {
        assertTrue(
            InstallRouting.pickerExtensions.containsAll(
                setOf("apk", "exe", "zip", "rar", "7z", "xapk", "apks", "apkm", "sh", "py", "html", "htm", "swf", "jgp")
            )
        )
    }
}
