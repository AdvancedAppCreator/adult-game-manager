package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedArchiveUpgradeRoutingTest {
    private val gameId = "11111111-1111-1111-1111-111111111111"

    private fun analysis(vararg names: String) = ManagedArchiveAnalysis(
        entryCount = 1,
        entryNames = listOf("Game.exe"),
        nameHints = names.map {
            ManagedNameHint(it, ManagedNameEvidence.ArchiveName, "$it.zip")
        },
    )

    private fun managedApp(
        label: String,
        path: String? = "/storage/emulated/0/Games/$label",
        id: String? = gameId,
    ) = InstalledApp(
        packageName = "managed:$id",
        label = label,
        versionName = "",
        versionCode = 0L,
        source = AppSource.Managed,
        managedGameId = id,
        managedDefaultRunner = ManagedRunnerKind.JoiPlay,
        storagePath = path,
    )

    @Test
    fun onlyManagedGameArchivesAreUpgradeEligible() {
        listOf(
            InstallRouting.Target.JoiPlay,
            InstallRouting.Target.Winlator,
            InstallRouting.Target.Kirikiroid,
            InstallRouting.Target.Managed,
        ).forEach { target ->
            assertTrue(
                target.toString(),
                InstallRouting.isManagedUpgradeEligible(InstallRouting.ArchiveRoute.Extract(target)),
            )
        }
        assertTrue(InstallRouting.isManagedUpgradeEligible(InstallRouting.ArchiveRoute.ChooseRunner))
        assertTrue(InstallRouting.isManagedUpgradeEligible(InstallRouting.ArchiveRoute.HtmlOnly))

        assertFalse(
            InstallRouting.isManagedUpgradeEligible(
                InstallRouting.ArchiveRoute.Extract(InstallRouting.Target.Android),
            ),
        )
        assertFalse(
            InstallRouting.isManagedUpgradeEligible(
                InstallRouting.ArchiveRoute.Extract(InstallRouting.Target.Auto),
            ),
        )
        assertFalse(
            InstallRouting.isManagedUpgradeEligible(
                InstallRouting.ArchiveRoute.ExtractNested("wrapper/inner.zip"),
            ),
        )
        assertFalse(InstallRouting.isManagedUpgradeEligible(InstallRouting.ArchiveRoute.Videos))
        assertFalse(InstallRouting.isManagedUpgradeEligible(InstallRouting.ArchiveRoute.Other))
        assertFalse(
            InstallRouting.isManagedUpgradeEligible(
                InstallRouting.ArchiveRoute.Unsupported(InstallRouting.splitBundleMessage),
            ),
        )
    }

    @Test
    fun matchingManagedGameOffersAnUpgrade() {
        val decision = ManagedArchiveUpgradeCoordinator.decide(
            route = InstallRouting.ArchiveRoute.ChooseRunner,
            analysis = analysis("Midnight Paradise"),
            apps = listOf(managedApp("Midnight Paradise")),
        )

        assertTrue(decision is ManagedArchiveUpgradeCoordinator.Decision.Upgrade)
        assertEquals(
            listOf("Midnight Paradise"),
            (decision as ManagedArchiveUpgradeCoordinator.Decision.Upgrade).matches.map { it.app.label },
        )
    }

    @Test
    fun aSharedGenericWordNeverOffersAnUpgrade() {
        listOf("Public Release", "Game Patch", "Paradise", "Compressed Build").forEach { name ->
            assertEquals(
                name,
                ManagedArchiveUpgradeCoordinator.Decision.InstallAsNew,
                ManagedArchiveUpgradeCoordinator.decide(
                    route = InstallRouting.ArchiveRoute.ChooseRunner,
                    analysis = analysis(name),
                    apps = listOf(managedApp("Midnight Paradise")),
                ),
            )
        }
    }

    @Test
    fun aVersionedArchiveOfTheSameGameStillRoutesToAnUpgrade() {
        val decision = ManagedArchiveUpgradeCoordinator.decide(
            route = InstallRouting.ArchiveRoute.ChooseRunner,
            analysis = analysis("Midnight Paradise 0 20 pc"),
            apps = listOf(managedApp("Midnight Paradise"), managedApp("Grandma's House")),
        )
        val matches = (decision as ManagedArchiveUpgradeCoordinator.Decision.Upgrade).matches
        assertEquals(listOf("Midnight Paradise"), matches.map { it.app.label })
        assertTrue(matches.single().reason.isNotBlank())
    }

    @Test
    fun noMatchFallsBackToInstallingAsNew() {
        assertEquals(
            ManagedArchiveUpgradeCoordinator.Decision.InstallAsNew,
            ManagedArchiveUpgradeCoordinator.decide(
                route = InstallRouting.ArchiveRoute.ChooseRunner,
                analysis = analysis("Something Entirely Different"),
                apps = listOf(managedApp("Midnight Paradise")),
            ),
        )
    }

    @Test
    fun androidAndBucketArchivesNeverOfferAnUpgrade() {
        val apps = listOf(managedApp("Midnight Paradise"))
        listOf(
            InstallRouting.ArchiveRoute.Extract(InstallRouting.Target.Android),
            InstallRouting.ArchiveRoute.Videos,
            InstallRouting.ArchiveRoute.Other,
            InstallRouting.ArchiveRoute.ExtractNested("wrapper/Midnight Paradise.zip"),
            InstallRouting.ArchiveRoute.Unsupported("nope"),
        ).forEach { route ->
            assertEquals(
                route.toString(),
                ManagedArchiveUpgradeCoordinator.Decision.InstallAsNew,
                ManagedArchiveUpgradeCoordinator.decide(route, analysis("Midnight Paradise"), apps),
            )
        }
    }

    @Test
    fun legacyAndIncompleteRowsAreNotUpgradeTargets() {
        val legacy = InstalledApp(
            packageName = "joiplay.legacy",
            label = "Midnight Paradise",
            versionName = "",
            versionCode = 0L,
            source = AppSource.JoiPlay,
            storagePath = "/storage/emulated/0/Games/Midnight Paradise",
        )
        val android = InstalledApp(
            packageName = "com.example.midnight",
            label = "Midnight Paradise",
            versionName = "1",
            versionCode = 1L,
            source = AppSource.Android,
        )
        val managedWithoutId = managedApp("Midnight Paradise", id = null)
        val managedWithoutPath = managedApp("Midnight Paradise", path = null)

        assertEquals(
            ManagedArchiveUpgradeCoordinator.Decision.InstallAsNew,
            ManagedArchiveUpgradeCoordinator.decide(
                InstallRouting.ArchiveRoute.ChooseRunner,
                analysis("Midnight Paradise"),
                listOf(legacy, android, managedWithoutId, managedWithoutPath),
            ),
        )
    }
}
