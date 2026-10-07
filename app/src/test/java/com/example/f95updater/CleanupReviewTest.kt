package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CleanupReviewTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun classifiesCurrentJoiPlayAndWinlatorRowsOnly() {
        val root = temporaryFolder.newFolder("Games")
        val joiPlayFolder = gameFolder(root, "JoiPlayGame")
        val winlatorFolder = gameFolder(root, "WinlatorGame")
        val oldBackupOnlyFolder = gameFolder(root, "OldBackupOnly")
        File(root, "configuration.joiback").writeText(oldBackupOnlyFolder.path)

        val report = CleanupReviewReporter.buildReport(
            root,
            listOf(
                app("jp", AppSource.JoiPlay, joiPlayFolder.path, joiPlayId = "j-1"),
                app("win", AppSource.Winlator, winlatorFolder.path, winlatorId = "w-1", containerId = 7),
                app("android", AppSource.Android, oldBackupOnlyFolder.path),
            ),
        )

        val games = report.managedGames.associateBy { it.packageName }
        assertEquals(setOf("jp", "win"), games.keys)
        assertEquals("j-1", games.getValue("jp").joiPlayGameId)
        assertEquals("w-1", games.getValue("win").winlatorGameId)
        assertEquals(7, games.getValue("win").winlatorContainerId)
        assertEquals(setOf(canonical(oldBackupOnlyFolder)), report.unassociatedFolders.map { it.path }.toSet())
    }

    @Test
    fun resolvesRelativePathsAndJoiPlayEngineSubdirectories() {
        val root = temporaryFolder.newFolder("Shared")
        val relativeGame = gameFolder(root, "Nested/RelativeGame")
        val joiPlayGame = gameFolder(root, "JoiPlayGame")
        val engineFolder = File(joiPlayGame, "www").apply { mkdir() }

        val report = CleanupReviewReporter.buildReport(
            root,
            listOf(
                app("relative", AppSource.Winlator, "Nested\\RelativeGame"),
                app("engine", AppSource.JoiPlay, engineFolder.path),
            ),
        )

        val games = report.managedGames.associateBy { it.packageName }
        assertEquals(canonical(relativeGame), games.getValue("relative").effectiveFolderPath)
        assertEquals(canonical(joiPlayGame), games.getValue("engine").effectiveFolderPath)
        assertTrue(report.unassociatedFolders.isEmpty())
        assertTrue(report.orphanGames.isEmpty())
    }

    @Test
    fun reportsMaximalUnassociatedSubtreesWithoutEnteringGames() {
        val root = temporaryFolder.newFolder("Root")
        val firstGame = gameFolder(root, "Container/Deeper/First")
        val secondGame = gameFolder(root, "Second")
        val nestedUnused = gameFolder(root, "Container/Unused/Child")
        val rootUnused = gameFolder(root, "UnusedAtRoot/Child")
        gameFolder(firstGame, "saves/internal")

        val report = CleanupReviewReporter.buildReport(
            root,
            listOf(
                app("first", AppSource.JoiPlay, firstGame.path),
                app("second", AppSource.Winlator, secondGame.path),
            ),
        )

        assertEquals(
            setOf(canonical(File(root, "Container/Unused")), canonical(File(root, "UnusedAtRoot"))),
            report.unassociatedFolders.map { it.path }.toSet(),
        )
        assertFalse(report.unassociatedFolders.any { it.path == canonical(nestedUnused) })
        assertFalse(report.unassociatedFolders.any { it.path == canonical(rootUnused) })
        assertFalse(report.unassociatedFolders.any { it.path.contains("/saves") })
    }

    @Test
    fun onlyDeterministicallyMissingGamesAreOrphans() {
        val root = temporaryFolder.newFolder("Root")
        val missingPath = File(root, "Missing").path

        val report = CleanupReviewReporter.buildReport(
            root,
            listOf(
                app("missing", AppSource.JoiPlay, missingPath),
                app("blank", AppSource.JoiPlay, "  "),
                app("null", AppSource.Winlator, null),
            ),
        )

        assertEquals(CleanupExistenceStatus.Missing, report.managedGames.first { it.packageName == "missing" }.existenceStatus)
        assertEquals(CleanupExistenceStatus.Unknown, report.managedGames.first { it.packageName == "blank" }.existenceStatus)
        assertEquals(CleanupExistenceStatus.Unknown, report.managedGames.first { it.packageName == "null" }.existenceStatus)
        assertNull(report.managedGames.first { it.packageName == "null" }.effectiveFolderPath)
        assertEquals(listOf("missing"), report.orphanGames.map { it.packageName })
    }

    @Test
    fun recordPointingAtRootDoesNotHideRootChildren() {
        val root = temporaryFolder.newFolder("Root")
        val child = gameFolder(root, "VisibleChild")

        val report = CleanupReviewReporter.buildReport(
            root,
            listOf(app("bad", AppSource.JoiPlay, root.path)),
        )

        assertEquals(CleanupExistenceStatus.Exists, report.managedGames.single().existenceStatus)
        assertEquals(setOf(canonical(child)), report.unassociatedFolders.map { it.path }.toSet())
    }

    @Test
    fun emitsProgressAndTextForEverySection() {
        val root = temporaryFolder.newFolder("Root")
        val progress = mutableListOf<CleanupReviewReporter.Progress>()

        val report = CleanupReviewReporter.buildReport(root, emptyList(), progress::add)
        val text = report.asText()

        assertTrue(progress.any { it.stage == "Classifying games" })
        assertTrue(progress.any { it.stage == "Scanning folders" })
        assertTrue(progress.any { it.stage == "Finalizing report" })
        assertTrue(text.contains("Managed games"))
        assertTrue(text.contains("Unassociated folders"))
        assertTrue(text.contains("Orphan games"))
        assertTrue(text.contains("Unreadable folders"))
    }

    @Test
    fun liveFilterMatchesAcrossGameAndFolderFields() {
        val report = CleanupReviewReport(
            rootPath = "C:/Games",
            managedGames = listOf(
                cleanupGame("jp", "Japanese Quest", "C:/Games/JapaneseQuest", AppSource.JoiPlay),
                cleanupGame("win", "Space Story", "C:/Games/SpaceStory", AppSource.Winlator),
            ),
            unassociatedFolders = listOf(
                CleanupReviewFolderEntry("Unused RPG", "C:/Games/Unused RPG", "C:/Games"),
            ),
            orphanGames = listOf(
                cleanupGame("gone", "Missing Mystery", "C:/Games/Missing", AppSource.Winlator),
            ),
            unreadableFolders = listOf("C:/Games/Private"),
            scannedFolderCount = 4,
            scanLimitReached = false,
        )

        assertEquals(listOf("jp"), filterCleanupReview(report, "japanese joiplay").managedGames.map { it.packageName })
        assertEquals(1, filterCleanupReview(report, "unused rpg").unassociatedFolders.size)
        assertEquals(listOf("gone"), filterCleanupReview(report, "missing").orphanGames.map { it.packageName })
        assertEquals(listOf("C:/Games/Private"), filterCleanupReview(report, "private").unreadableFolders)
    }

    @Test
    fun blankFilterReturnsOriginalLists() {
        val report = CleanupReviewReporter.buildReport(temporaryFolder.newFolder("BlankFilter"), emptyList())

        val filtered = filterCleanupReview(report, "   ")

        assertTrue(filtered.managedGames === report.managedGames)
        assertTrue(filtered.unassociatedFolders === report.unassociatedFolders)
        assertTrue(filtered.orphanGames === report.orphanGames)
        assertTrue(filtered.unreadableFolders === report.unreadableFolders)
    }

    @Test
    fun onlyExistingOrUnknownGameDirectoriesCanBeOpened() {
        val existing = cleanupGame("existing", "Existing", "C:/Games/Existing", AppSource.Winlator)
        val unknown = existing.copy(
            packageName = "unknown",
            effectiveFolderPath = null,
            storagePath = " C:/Games/Unknown ",
            existenceStatus = CleanupExistenceStatus.Unknown,
        )
        val missing = existing.copy(
            packageName = "missing",
            existenceStatus = CleanupExistenceStatus.Missing,
        )

        assertEquals("C:/Games/Existing", cleanupGameOpenablePath(existing))
        assertEquals("C:/Games/Unknown", cleanupGameOpenablePath(unknown))
        assertNull(cleanupGameOpenablePath(missing))
        assertNull(cleanupGameOpenablePath(unknown.copy(storagePath = "  ")))
    }

    private fun app(
        packageName: String,
        source: AppSource,
        storagePath: String?,
        joiPlayId: String? = null,
        winlatorId: String? = null,
        containerId: Int? = null,
    ) = InstalledApp(
        packageName = packageName,
        label = packageName,
        versionName = "",
        versionCode = 0,
        source = source,
        storagePath = storagePath,
        joiPlayGameId = joiPlayId,
        winlatorGameId = winlatorId,
        winlatorContainerId = containerId,
    )

    private fun gameFolder(root: File, relativePath: String): File =
        File(root, relativePath).apply { mkdirs() }

    private fun cleanupGame(
        packageName: String,
        title: String,
        path: String,
        source: AppSource,
    ) = CleanupGameEntry(
        packageName = packageName,
        source = source,
        title = title,
        storagePath = path,
        effectiveFolderPath = path,
        joiPlayGameId = null,
        winlatorGameId = null,
        winlatorContainerId = null,
        existenceStatus = if (packageName == "gone") {
            CleanupExistenceStatus.Missing
        } else {
            CleanupExistenceStatus.Exists
        },
    )

    private fun canonical(file: File): String =
        file.canonicalPath.replace('\\', '/').trimEnd('/')
}
