package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PatchInstallPlanningTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun staged(vararg entries: Pair<String, Long>) =
        entries.map { PatchStagedFile(it.first, it.second) }

    @Test
    fun separatesAdditionsFromReplacementsAndRecordsNewFolders() {
        val destination = temp.newFolder("game")
        File(destination, "script.rpy").writeText("old")
        val outcome = PatchInstallPlanner.plan(
            destination,
            staged(
                "script.rpy" to 3L,
                "gallery.rpy" to 5L,
                "images/thumb.png" to 7L,
                "images/sub/deep.png" to 9L,
            ),
        )
        val plan = (outcome as PatchPlanOutcome.Ready).plan
        assertEquals(listOf("script.rpy"), plan.replacements.map { it.relativePath })
        assertEquals(
            listOf("gallery.rpy", "images/sub/deep.png", "images/thumb.png"),
            plan.additions.map { it.relativePath },
        )
        assertEquals(listOf("images", "images/sub"), plan.createdDirectories)
        assertEquals(24L, plan.totalBytes)
        assertEquals(4, plan.fileCount)
    }

    @Test
    fun refusesWhenTheDestinationDoesNotExist() {
        val missing = File(temp.root, "nope")
        val outcome = PatchInstallPlanner.plan(missing, staged("a.rpy" to 1L))
        assertTrue((outcome as PatchPlanOutcome.Rejected).reason.contains("does not exist"))
    }

    @Test
    fun refusesWhenATargetIsAlreadyAFolder() {
        val destination = temp.newFolder("game")
        File(destination, "script.rpy").mkdirs()
        val outcome = PatchInstallPlanner.plan(destination, staged("script.rpy" to 1L))
        assertTrue((outcome as PatchPlanOutcome.Rejected).reason.contains("already exists as a folder"))
    }

    @Test
    fun refusesWhenAnAncestorIsAFile() {
        val destination = temp.newFolder("game")
        File(destination, "images").writeText("not a folder")
        val outcome = PatchInstallPlanner.plan(destination, staged("images/thumb.png" to 1L))
        assertTrue((outcome as PatchPlanOutcome.Rejected).reason.contains("exists as a file"))
    }

    @Test
    fun refusesAnEmptyChangeSet() {
        val destination = temp.newFolder("game")
        val outcome = PatchInstallPlanner.plan(destination, emptyList())
        assertTrue((outcome as PatchPlanOutcome.Rejected).reason.contains("would not change any file"))
    }

    @Test
    fun anAdditionThatAppearsAfterPlanningIsRefusedInsteadOfOverwritten() {
        val destination = temp.newFolder("game")
        File(destination, "script.rpy").writeText("old")
        val plan = (
            PatchInstallPlanner.plan(
                destination,
                staged("script.rpy" to 3L, "gallery.rpy" to 5L, "images/thumb.png" to 7L),
            ) as PatchPlanOutcome.Ready
            ).plan
        assertEquals(
            listOf("gallery.rpy", "images/thumb.png"),
            plan.additions.map { it.relativePath },
        )
        assertNull(PatchInstallTransaction.additionsChangedSincePreview(plan))

        // Something else creates the file between the preview and the install.
        File(destination, "gallery.rpy").writeText("written by someone else")
        val reason = PatchInstallTransaction.additionsChangedSincePreview(plan)!!
        assertTrue(reason.contains("changed since the patch was previewed"))
        assertTrue(reason.contains("'gallery.rpy'"))
        assertTrue(reason.contains("nothing was changed"))
        // The addition stays an addition: it is never promoted to a replacement.
        assertEquals(
            listOf("gallery.rpy", "images/thumb.png"),
            plan.additions.map { it.relativePath },
        )
        assertEquals(listOf("script.rpy"), plan.replacements.map { it.relativePath })
    }

    @Test
    fun anAdditionThatAppearsAsAFolderAfterPlanningIsAlsoRefused() {
        val destination = temp.newFolder("game")
        File(destination, "script.rpy").writeText("old")
        val plan = (
            PatchInstallPlanner.plan(destination, staged("script.rpy" to 3L, "extra.rpy" to 4L))
                as PatchPlanOutcome.Ready
            ).plan
        File(destination, "extra.rpy").mkdirs()
        assertTrue(
            PatchInstallTransaction.additionsChangedSincePreview(plan)!!
                .contains("changed since the patch was previewed"),
        )
    }

    @Test
    fun journalRecordsSurviveASerialisationRoundTrip() {
        val record = PatchTransactionRecord(
            transactionId = "tx-1",
            phase = PatchTransactionPhase.Committing,
            managedGameId = "game-1",
            label = "Sample Game",
            storagePath = "/games/Sample",
            destinationRoot = "/games/Sample/game",
            workDir = "/games/.agm-patch-tx-1",
            backupDir = "/games/.agm-patch-tx-1/backup",
            archiveName = "patch.rar",
            added = listOf(PatchAddedFile("gallery.rpy", "aa", 5L)),
            replaced = listOf(PatchReplacedFile("script.rpy", "bb", 3L)),
            createdDirectories = listOf("images"),
        )
        val decoded = PatchTransactionStore.decode(PatchTransactionStore.encode(listOf(record)))
        assertEquals(listOf(record), decoded)
        assertTrue(record.isUnresolved)
        assertTrue(!record.copy(phase = PatchTransactionPhase.Committed).isUnresolved)
    }
}
