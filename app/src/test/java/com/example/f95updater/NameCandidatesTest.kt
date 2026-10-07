package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NameCandidatesTest {
    @Test
    fun exactGenericExecutableBasenamesAreIgnored() {
        assertTrue(isGenericExecutableBasename("game"))
        assertTrue(isGenericExecutableBasename("Game"))
        assertTrue(isGenericExecutableBasename("mygame"))
        assertTrue(isGenericExecutableBasename("start"))
        assertTrue(isGenericExecutableBasename("setup"))
        assertTrue(isGenericExecutableBasename("launcher"))
        assertTrue(isGenericExecutableBasename("payload"))
        assertTrue(isGenericExecutableBasename("update"))
        assertTrue(isGenericExecutableBasename("patch"))
        assertTrue(isGenericExecutableBasename("uninstall"))
        assertTrue(isGenericExecutableBasename("_uninst"))
        assertTrue(isGenericExecutableBasename("nwjc"))
        assertTrue(isGenericExecutableBasename("notification_helper"))
        assertTrue(isGenericExecutableBasename("config"))
    }

    @Test
    fun specificNamesContainingGenericSubstringsAreNotIgnored() {
        // "TamaraExposed3" doesn't equal any generic basename, only exact matches are
        // filtered so a substring like "game" appearing inside a specific name must survive.
        assertFalse(isGenericExecutableBasename("TamaraExposed3"))
        assertFalse(isGenericExecutableBasename("MyGameOfThronesParody"))
        assertFalse(isGenericExecutableBasename("GameOverStudio"))
    }

    @Test
    fun executableNameCandidateStripsDirectoryAndExtension() {
        val candidate = executableNameCandidate("""game\TamaraExposed3.exe""")

        assertEquals(NameCandidate("TamaraExposed3", NameCandidateSource.SpecificExecutable), candidate)
    }

    @Test
    fun executableNameCandidateIsNullForGenericOrBlankPaths() {
        assertNull(executableNameCandidate("""C:\Games\Foo\game.exe"""))
        assertNull(executableNameCandidate("start.exe"))
        assertNull(executableNameCandidate(null))
        assertNull(executableNameCandidate("   "))
    }

    @Test
    fun nameCandidatesForCoversLabelReadmeExecutableAndFolderSources() {
        val app = InstalledApp(
            packageName = "joiplay:tamara",
            label = "Tamara Exposed",
            launcherLabel = "TE",
            versionName = "",
            versionCode = 0L,
            source = AppSource.JoiPlay,
            storagePath = "/games/TamaraFolder",
            storageFolderName = "TamaraFolder",
            joiPlayExecFile = "TamaraExposed3.exe",
            readmeTitle = "Original README Title",
        )

        val candidates = nameCandidatesFor(app)

        assertTrue(NameCandidate("Tamara Exposed", NameCandidateSource.AppLabel) in candidates)
        assertTrue(NameCandidate("TE", NameCandidateSource.LauncherLabel) in candidates)
        assertTrue(NameCandidate("Original README Title", NameCandidateSource.Readme) in candidates)
        assertTrue(NameCandidate("TamaraExposed3", NameCandidateSource.SpecificExecutable) in candidates)
        assertTrue(NameCandidate("TamaraFolder", NameCandidateSource.FolderName) in candidates)
    }

    @Test
    fun nameCandidatesForDropsGenericWinlatorExecutableButKeepsSpecificOne() {
        val generic = InstalledApp(
            packageName = "winlator:1",
            label = "Some Game",
            versionName = "",
            versionCode = 0L,
            source = AppSource.Winlator,
            winlatorExecutablePath = "C:/Games/Foo/start.exe",
        )
        val specific = generic.copy(winlatorExecutablePath = "C:/Games/Foo/FooQuestVN.exe")

        assertTrue(nameCandidatesFor(generic).none { it.source == NameCandidateSource.SpecificExecutable })
        assertTrue(
            NameCandidate("FooQuestVN", NameCandidateSource.SpecificExecutable) in nameCandidatesFor(specific),
        )
    }

    @Test
    fun genericWrapperFolderNamesAreRecognized() {
        assertTrue(isGenericWrapperFolderName("game"))
        assertTrue(isGenericWrapperFolderName("WWW"))
        assertTrue(isGenericWrapperFolderName("app"))
        assertTrue(isGenericWrapperFolderName("src"))
        assertTrue(isGenericWrapperFolderName("resources"))
        assertTrue(isGenericWrapperFolderName("JoiPlay"))
        assertTrue(isGenericWrapperFolderName("pc"))
        assertFalse(isGenericWrapperFolderName("TamaraFolder"))
    }

    @Test
    fun nameCandidatesForDropsGenericWrapperFolderAndBasenameCandidates() {
        // A storage folder literally named "game"/"pc"/"joiplay" etc. must never itself become
        // a FolderName/BasenameFallback candidate: it could otherwise exact/prefix-match an
        // unrelated catalog title of the same generic word.
        val genericStorageFolderName = InstalledApp(
            packageName = "joiplay:1",
            label = "Some Game",
            versionName = "",
            versionCode = 0L,
            source = AppSource.JoiPlay,
            storagePath = "pc",
            storageFolderName = "pc",
        )
        val candidates = nameCandidatesFor(genericStorageFolderName)

        assertTrue(candidates.none { it.source == NameCandidateSource.FolderName })
        assertTrue(candidates.none { it.source == NameCandidateSource.BasenameFallback })
    }

    @Test
    fun nameCandidatesForStillKeepsSpecificFolderAndBasenameCandidates() {
        val app = InstalledApp(
            packageName = "joiplay:2",
            label = "Some Game",
            versionName = "",
            versionCode = 0L,
            source = AppSource.JoiPlay,
            storagePath = "/games/TamaraFolder",
            storageFolderName = "TamaraFolder",
        )
        val candidates = nameCandidatesFor(app)

        assertTrue(NameCandidate("TamaraFolder", NameCandidateSource.FolderName) in candidates)
        assertTrue(NameCandidate("TamaraFolder", NameCandidateSource.BasenameFallback) !in candidates)
    }

    @Test
    fun genericInstructionAndWrapperTitlesAreRemoved() {
        val app = InstalledApp(
            packageName = "managed:intro",
            label = "-----　はじめに",
            launcherLabel = "ReiPatcher",
            versionName = "",
            versionCode = 0L,
            source = AppSource.Managed,
            storagePath = "/games/game",
            storageFolderName = "game",
            readmeTitle = "Ver X.XX",
        )

        assertTrue(nameCandidatesFor(app).isEmpty())
        assertTrue(isGenericTitleCandidate("Ver 1.05"))
        assertTrue(isGenericTitleCandidate("Version 2.3a"))
    }

    @Test
    fun reversibleJapaneseMojibakeIsRecoveredFromFolderNames() {
        assertEquals("アマナアライブ", recoverJapaneseMojibake("âAâ}âiâAâëâCâu"))

        val app = InstalledApp(
            packageName = "managed:amana",
            label = "Install",
            versionName = "",
            versionCode = 0L,
            source = AppSource.Managed,
            storagePath = "/games/âAâ}âiâAâëâCâu",
            storageFolderName = "âAâ}âiâAâëâCâu",
        )

        assertTrue(NameCandidate("アマナアライブ", NameCandidateSource.FolderName) in nameCandidatesFor(app))
    }

    @Test
    fun ordinaryLatinTextIsNeverSpeculativelyRecoded() {
        assertNull(recoverJapaneseMojibake("Amana Alive"))
        assertNull(recoverJapaneseMojibake("otomi-games.com_CJDEBC73"))
    }

    @Test
    fun exactProductCodesAndOtomiArchiveAliasesAreExtractedSeparatelyFromTitles() {
        val productCodeApp = InstalledApp(
            packageName = "managed:rj",
            label = "----- はじめに",
            launcherLabel = "[Kimochi] RJ01154832",
            versionName = "",
            versionCode = 0L,
            source = AppSource.Managed,
        )
        val archiveAliasApp = InstalledApp(
            packageName = "managed:otomi",
            label = "otomi-games.com_CJDEBC73",
            versionName = "",
            versionCode = 0L,
            source = AppSource.Managed,
            storagePath = "/games/otomi-games.com_CJDEBC73",
        )

        assertTrue(
            CatalogIdentityCandidate(
                CatalogIdentityKind.ProductCode,
                "RJ01154832",
                NameCandidateSource.LauncherLabel,
            ) in identityCandidatesFor(productCodeApp),
        )
        assertTrue(
            identityCandidatesFor(archiveAliasApp).any {
                it.kind == CatalogIdentityKind.DownloadAlias &&
                    it.value == "otomi-games.com_cjdebc73"
            },
        )
        val equivalentCodes = productCodeApp.copy(
            label = "[Store] RE12345 BJ23456 VE34567 d_595475",
            launcherLabel = null,
        )
        assertEquals(
            setOf("RE12345", "BJ23456", "VE34567", "D_595475"),
            identityCandidatesFor(equivalentCodes)
                .filter { it.kind == CatalogIdentityKind.ProductCode }
                .map { it.value }
                .toSet(),
        )
    }
}
