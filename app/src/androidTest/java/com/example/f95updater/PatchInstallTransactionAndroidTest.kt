package com.example.f95updater

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PatchInstallTransactionAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var workspace: File
    private lateinit var library: File
    private lateinit var storageRoot: File
    private lateinit var gameFolder: File
    private lateinit var staging: File
    private val createdGameIds = mutableListOf<String>()

    @Before
    fun setUp() {
        clearJournal()
        workspace = File(context.cacheDir, "patch-tx-${System.nanoTime()}").apply { mkdirs() }
        library = File(workspace, "library").apply { mkdirs() }
        storageRoot = File(library, "Sample Game").apply { mkdirs() }
        gameFolder = File(storageRoot, "game").apply { mkdirs() }
        File(storageRoot, "renpy").mkdirs()
        File(gameFolder, "script.rpy").writeText("original script\n")
        File(gameFolder, "options.rpy").writeText("define config.version = \"0.2\"\n")
        staging = File(workspace, "staging").apply { mkdirs() }
    }

    @After
    fun tearDown() = runBlocking {
        PatchInstallTransaction.preJournalFault = null
        library.setWritable(true)
        clearJournal()
        val store = ManagedGameStore(context)
        createdGameIds.forEach { id -> runCatching { store.delete(id) } }
        createdGameIds.clear()
        workspace.deleteRecursively()
        Unit
    }

    private fun clearJournal() {
        runCatching { PatchTransactionStore.load(context) }
            .getOrDefault(emptyList())
            .forEach { record ->
                PatchTransactionStore.remove(context, record.transactionId)
                File(record.workDir).deleteRecursively()
            }
    }

    /**
     * The rollback guard re-reads the game from [ManagedGameStore], so every test game must be a
     * real library entry, exactly as in production.
     */
    private suspend fun managedGame(root: File = storageRoot): ManagedGame =
        ManagedGameStore(context).create(
            ManagedGameDraft(
                storagePath = root.absolutePath,
                label = "Sample Game",
                runnerBindings = listOf(
                    ManagedRunnerBinding.JoiPlay(type = "renpy", execFile = "script.rpy"),
                ),
            ),
        ).also { createdGameIds += it.id }

    private fun stage(vararg entries: Pair<String, String>): List<PatchStagedFile> =
        entries.map { (relative, content) ->
            val file = File(staging, relative)
            file.parentFile?.mkdirs()
            file.writeText(content)
            PatchStagedFile(relative, file.length())
        }

    private fun plan(staged: List<PatchStagedFile>): PatchInstallPlan =
        (PatchInstallPlanner.plan(gameFolder, staged) as PatchPlanOutcome.Ready).plan

    @Test
    fun installReplacesBacksUpAndVerifiesEveryFile() = runBlocking {
        val game = managedGame()
        val staged = stage(
            "script.rpy" to "patched script\n",
            "gallery.rpy" to "gallery\n",
            "images/thumb.txt" to "thumb\n",
        )
        val result = PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(staged)) {}

        assertEquals(2, result.addedCount)
        assertEquals(1, result.replacedCount)
        assertEquals("patched script\n", File(gameFolder, "script.rpy").readText())
        assertEquals("gallery\n", File(gameFolder, "gallery.rpy").readText())
        assertEquals("thumb\n", File(gameFolder, "images/thumb.txt").readText())
        assertTrue(gameFolder.listFiles()!!.none { it.name.endsWith(".agm-patch-part") })

        val record = PatchTransactionStore.find(context, game.id)
        assertNotNull(record)
        assertEquals(PatchTransactionPhase.Committed, record!!.phase)
        assertFalse(record.isUnresolved)
        assertEquals("original script\n", File(record.backupDir, "script.rpy").readText())
    }

    @Test
    fun aSecondPatchIsRefusedWhileAnEarlierOneIsStillRecorded() = runBlocking {
        val game = managedGame()
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(stage("script.rpy" to "a\n"))) {}
        val second = runCatching {
            PatchInstallTransaction.install(
                context,
                game,
                "other.zip",
                staging,
                plan(stage("script.rpy" to "b\n")),
            ) {}
        }
        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull()!!.message!!.contains("already has patch transaction"))
        assertEquals("a\n", File(gameFolder, "script.rpy").readText())
    }

    @Test
    fun rollbackRestoresOriginalsAndRemovesAddedFiles() = runBlocking {
        val game = managedGame()
        val staged = stage(
            "script.rpy" to "patched script\n",
            "gallery.rpy" to "gallery\n",
            "images/thumb.txt" to "thumb\n",
        )
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(staged)) {}
        val record = PatchTransactionStore.find(context, game.id)!!

        val outcome = PatchInstallTransaction.rollback(context, record)
        assertTrue(outcome is PatchRollbackOutcome.RolledBack)
        assertEquals("original script\n", File(gameFolder, "script.rpy").readText())
        assertFalse(File(gameFolder, "gallery.rpy").exists())
        assertFalse(File(gameFolder, "images/thumb.txt").exists())
        assertFalse(File(gameFolder, "images").exists())
        assertNull(PatchTransactionStore.find(context, game.id))
        assertFalse(File(record.workDir).exists())
    }

    @Test
    fun recoveryRollsBackATransactionInterruptedWhileCommitting() = runBlocking {
        val game = managedGame()
        val staged = stage("script.rpy" to "patched script\n", "gallery.rpy" to "gallery\n")
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(staged)) {}
        val committed = PatchTransactionStore.find(context, game.id)!!

        // Simulate process death between the journal write and the final verification.
        PatchTransactionStore.save(context, committed.copy(phase = PatchTransactionPhase.Committing))

        val outcomes = recoverPendingPatchInstalls(context)
        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is PatchRecoveryOutcome.RolledBack)
        assertEquals("original script\n", File(gameFolder, "script.rpy").readText())
        assertFalse(File(gameFolder, "gallery.rpy").exists())
        assertNull(PatchTransactionStore.find(context, game.id))
    }

    @Test
    fun recoveryReportsUnresolvedStateWhenTheBackupIsGone() = runBlocking {
        val game = managedGame()
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(stage("script.rpy" to "patched\n"))) {}
        val committed = PatchTransactionStore.find(context, game.id)!!
        File(committed.backupDir).deleteRecursively()
        PatchTransactionStore.save(context, committed.copy(phase = PatchTransactionPhase.Committing))

        val outcomes = recoverPendingPatchInstalls(context)
        val unresolved = outcomes.single() as PatchRecoveryOutcome.Unresolved
        assertTrue(unresolved.message.contains("could not be fully restored"))
        // The journal is deliberately kept so the state is never assumed resolved.
        val kept = PatchTransactionStore.find(context, game.id)!!
        assertEquals(PatchTransactionPhase.RollingBack, kept.phase)
        assertNotNull(kept.unresolvedReason)
    }

    @Test
    fun completedTransactionsAreLeftAloneByRecovery() = runBlocking {
        val game = managedGame()
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(stage("script.rpy" to "patched\n"))) {}
        assertTrue(recoverPendingPatchInstalls(context).isEmpty())
        assertEquals("patched\n", File(gameFolder, "script.rpy").readText())
    }

    @Test
    fun discardingTheRollbackPointRemovesTheBackupAndTheRecord() = runBlocking {
        val game = managedGame()
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(stage("script.rpy" to "patched\n"))) {}
        val record = PatchTransactionStore.find(context, game.id)!!
        assertTrue(PatchInstallTransaction.discardRollbackPoint(context, record))
        assertNull(PatchTransactionStore.find(context, game.id))
        assertFalse(File(record.workDir).exists())
        assertEquals("patched\n", File(gameFolder, "script.rpy").readText())
    }

    @Test
    fun aFileThatAppearsAfterPlanningStopsTheInstallWithoutTouchingIt() = runBlocking {
        val game = managedGame()
        val staged = stage("script.rpy" to "patched\n", "gallery.rpy" to "gallery\n")
        val plan = plan(staged)
        assertEquals(listOf("gallery.rpy"), plan.additions.map { it.relativePath })

        // Something else creates the planned addition between preview and install.
        val intruder = File(gameFolder, "gallery.rpy")
        intruder.writeText("written by something else\n")
        val intruderBytes = intruder.readBytes()

        val outcome = runCatching {
            PatchInstallTransaction.install(context, game, "patch.zip", staging, plan) {}
        }
        assertTrue(outcome.isFailure)
        val message = outcome.exceptionOrNull()!!.message!!
        assertTrue(message.contains("changed since the patch was previewed"))
        assertTrue(message.contains("gallery.rpy"))

        // The appeared file is byte-identical and was never treated as a replacement.
        assertArrayEquals(intruderBytes, intruder.readBytes())
        assertEquals("original script\n", File(gameFolder, "script.rpy").readText())
        assertNull(PatchTransactionStore.find(context, game.id))
        assertTrue(PatchTransactionStore.load(context).isEmpty())
        assertTrue(library.listFiles()!!.none { it.name.startsWith(".agm-patch-") })
        assertTrue(storageRoot.listFiles()!!.none { it.name.startsWith(".agm-patch-") })
        assertTrue(gameFolder.listFiles()!!.none { it.name.endsWith(".agm-patch-part") })
    }

    @Test
    fun startupRecoveryLeavesARecordThatCommittedWhileItWaited() = runBlocking {
        val game = managedGame()
        val staged = stage("script.rpy" to "patched script\n", "gallery.rpy" to "gallery\n")
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(staged)) {}
        val committed = PatchTransactionStore.find(context, game.id)!!
        assertEquals(PatchTransactionPhase.Committed, committed.phase)

        // A snapshot taken before the commit finished: unresolved in memory, committed on disk.
        val stale = committed.copy(phase = PatchTransactionPhase.Committing)
        assertTrue(stale.isUnresolved)

        assertNull(PatchInstallTransaction.rollbackIfStillUnresolved(context, stale.transactionId))

        // Nothing was rolled back and the committed record was not altered.
        assertEquals("patched script\n", File(gameFolder, "script.rpy").readText())
        assertEquals("gallery\n", File(gameFolder, "gallery.rpy").readText())
        val after = PatchTransactionStore.find(context, game.id)!!
        assertEquals(PatchTransactionPhase.Committed, after.phase)
        assertNull(after.unresolvedReason)
        assertTrue(File(after.backupDir, "script.rpy").isFile)
    }

    @Test
    fun explicitRollbackOfACommittedRecordStillWorks() = runBlocking {
        val game = managedGame()
        val staged = stage("script.rpy" to "patched script\n", "gallery.rpy" to "gallery\n")
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(staged)) {}
        val committed = PatchTransactionStore.find(context, game.id)!!

        val outcome = PatchInstallTransaction.rollback(context, committed)
        assertTrue(outcome is PatchRollbackOutcome.RolledBack)
        assertEquals("original script\n", File(gameFolder, "script.rpy").readText())
        assertFalse(File(gameFolder, "gallery.rpy").exists())
        assertNull(PatchTransactionStore.find(context, game.id))
    }

    @Test
    fun aRecordIsRolledBackOnlyOnceWhenRecoveryRunsConcurrently() = runBlocking {
        val game = managedGame()
        val staged = stage("script.rpy" to "patched script\n", "gallery.rpy" to "gallery\n")
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(staged)) {}
        val committed = PatchTransactionStore.find(context, game.id)!!
        PatchTransactionStore.save(context, committed.copy(phase = PatchTransactionPhase.Committing))

        val results = coroutineScope {
            (1..4).map { async(Dispatchers.Default) { recoverPendingPatchInstalls(context) } }.awaitAll()
        }
        // Every caller starts from the same snapshot; only the one that wins the lock acts.
        assertEquals(1, results.sumOf { outcomes -> outcomes.size })
        assertTrue(results.flatten().single() is PatchRecoveryOutcome.RolledBack)
        assertEquals("original script\n", File(gameFolder, "script.rpy").readText())
        assertFalse(File(gameFolder, "gallery.rpy").exists())
        assertNull(PatchTransactionStore.find(context, game.id))
    }

    @Test
    fun aTamperedStagedFileStopsTheInstallBeforeAnythingChanges() = runBlocking {
        val game = managedGame()
        val staged = stage("script.rpy" to "patched\n")
        val declared = staged.map { it.copy(size = it.size + 1) }
        val outcome = runCatching {
            PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(declared)) {}
        }
        assertTrue(outcome.isFailure)
        assertTrue(outcome.exceptionOrNull()!!.message!!.contains("declared"))
        assertEquals("original script\n", File(gameFolder, "script.rpy").readText())
        assertNull(PatchTransactionStore.find(context, game.id))
        assertTrue(library.listFiles()!!.none { it.name.startsWith(".agm-patch-") })
    }

    // ------------------------------------------------- pre-journal fault injection
    //
    // Before the journal entry exists, this transaction's work folder has no owner but the install
    // call itself: no recovery pass can find it, and nothing outside it has been touched. The
    // handled failures above already cleaned it up inline, but an *unexpected* IO error from a
    // digest, a copy or the backup re-read escaped straight past that cleanup and left a folder
    // full of backups sitting in the library forever. Each test below makes exactly one of those
    // primitives throw and proves the same three things: the error reaches the caller, no work
    // folder and no journal entry survive anywhere, and the installed game is byte-for-byte
    // untouched.

    private fun installedBytes(): Map<String, ByteArray> =
        gameFolder.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(gameFolder).path to it.readBytes() }

    private fun assertNoTraceOfATransaction(game: ManagedGame, before: Map<String, ByteArray>) {
        assertNull(PatchTransactionStore.find(context, game.id))
        assertTrue(PatchTransactionStore.load(context).isEmpty())
        assertTrue(
            "no patch work folder may survive in the library",
            library.listFiles()!!.none { it.name.startsWith(".agm-patch-") },
        )
        assertTrue(
            "no patch work folder may survive beside the game",
            storageRoot.listFiles()!!.none { it.name.startsWith(".agm-patch-") },
        )
        assertTrue(gameFolder.listFiles()!!.none { it.name.endsWith(".agm-patch-part") })
        val after = installedBytes()
        assertEquals(before.keys, after.keys)
        before.forEach { (relative, bytes) -> assertArrayEquals(relative, bytes, after.getValue(relative)) }
    }

    /** Fails one pre-journal primitive with an unchecked IO error. */
    private fun failAt(point: PatchInstallTransaction.PatchInstallFaultPoint, message: String) {
        PatchInstallTransaction.preJournalFault = { fired -> if (fired == point) throw java.io.IOException(message) }
    }

    @Test
    fun aFailureHashingAStagedFileLeavesNoWorkFolderAndNoJournal() = runBlocking {
        val game = managedGame()
        val before = installedBytes()
        failAt(PatchInstallTransaction.PatchInstallFaultPoint.SourceHash, "injected staged-digest failure")
        val outcome = runCatching {
            PatchInstallTransaction.install(
                context,
                game,
                "patch.zip",
                staging,
                plan(stage("script.rpy" to "patched\n", "gallery.rpy" to "gallery\n")),
            ) {}
        }
        assertTrue(outcome.isFailure)
        assertEquals("injected staged-digest failure", outcome.exceptionOrNull()!!.message)
        assertNoTraceOfATransaction(game, before)
    }

    @Test
    fun aFailureHashingTheOriginalLeavesNoWorkFolderAndNoJournal() = runBlocking {
        val game = managedGame()
        val before = installedBytes()
        failAt(PatchInstallTransaction.PatchInstallFaultPoint.OriginalHash, "injected original-digest failure")
        val outcome = runCatching {
            PatchInstallTransaction.install(
                context,
                game,
                "patch.zip",
                staging,
                plan(stage("script.rpy" to "patched\n", "gallery.rpy" to "gallery\n")),
            ) {}
        }
        assertTrue(outcome.isFailure)
        assertEquals("injected original-digest failure", outcome.exceptionOrNull()!!.message)
        assertNoTraceOfATransaction(game, before)
    }

    @Test
    fun aFailureCopyingTheBackupLeavesNoWorkFolderAndNoJournal() = runBlocking {
        val game = managedGame()
        val before = installedBytes()
        failAt(PatchInstallTransaction.PatchInstallFaultPoint.BackupCopy, "injected backup-copy failure")
        val outcome = runCatching {
            PatchInstallTransaction.install(
                context,
                game,
                "patch.zip",
                staging,
                plan(stage("script.rpy" to "patched\n", "gallery.rpy" to "gallery\n")),
            ) {}
        }
        assertTrue(outcome.isFailure)
        assertEquals("injected backup-copy failure", outcome.exceptionOrNull()!!.message)
        assertNoTraceOfATransaction(game, before)
    }

    @Test
    fun aFailureHashingTheBackupLeavesNoWorkFolderAndNoJournal() = runBlocking {
        val game = managedGame()
        val before = installedBytes()
        failAt(PatchInstallTransaction.PatchInstallFaultPoint.BackupHash, "injected backup-digest failure")
        val outcome = runCatching {
            PatchInstallTransaction.install(
                context,
                game,
                "patch.zip",
                staging,
                plan(stage("script.rpy" to "patched\n", "gallery.rpy" to "gallery\n")),
            ) {}
        }
        assertTrue(outcome.isFailure)
        assertEquals("injected backup-digest failure", outcome.exceptionOrNull()!!.message)
        assertNoTraceOfATransaction(game, before)
    }

    @Test
    fun aFailedPreJournalInstallLeavesTheGameInstallableAgain() = runBlocking {
        // The leak was not only wasted space: a surviving work folder next to the library is
        // indistinguishable from a real rollback point to anyone reading the folder, and the retry
        // must still be able to run a clean transaction end to end.
        val game = managedGame()
        failAt(PatchInstallTransaction.PatchInstallFaultPoint.BackupCopy, "injected backup-copy failure")
        val failed = runCatching {
            PatchInstallTransaction.install(
                context,
                game,
                "patch.zip",
                staging,
                plan(stage("script.rpy" to "patched\n")),
            ) {}
        }
        assertTrue(failed.isFailure)
        assertEquals("original script\n", File(gameFolder, "script.rpy").readText())
        assertTrue(library.listFiles()!!.none { it.name.startsWith(".agm-patch-") })

        PatchInstallTransaction.preJournalFault = null
        val retried = PatchInstallTransaction.install(
            context,
            game,
            "patch.zip",
            staging,
            plan(stage("script.rpy" to "patched\n")),
        ) {}
        assertEquals(1, retried.replacedCount)
        assertEquals("patched\n", File(gameFolder, "script.rpy").readText())
        val record = PatchTransactionStore.find(context, game.id)
        assertNotNull(record)
        assertEquals(PatchTransactionPhase.Committed, record!!.phase)
        // Exactly one work folder exists now: the committed transaction's rollback point.
        assertEquals(
            listOf(record.workDir),
            library.listFiles()!!.filter { it.name.startsWith(".agm-patch-") }.map { it.absolutePath },
        )
    }

    // ------------------------------------------------- lifecycle reconciliation

    private fun relocationTarget(): File =
        File(library, "Sample Game v2").apply { mkdirs(); File(this, "game").mkdirs() }

    private fun replacementFor(game: ManagedGame, root: File): ManagedGame = validateManagedGame(
        game.copy(
            storagePath = root.absolutePath,
            canonicalPath = canonicalManagedGamePath(root.absolutePath),
        ),
    )

    @Test
    fun deletingTheGameDiscardsItsRollbackPointAndBackup() = runBlocking {
        val game = managedGame()
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(stage("script.rpy" to "patched\n"))) {}
        val record = PatchTransactionStore.find(context, game.id)!!
        assertTrue(File(record.workDir).isDirectory)

        assertTrue(ManagedGameStore(context).delete(game.id))

        assertTrue(PatchTransactionStore.load(context).isEmpty())
        assertFalse(File(record.workDir).exists())
    }

    @Test
    fun rollingBackAfterTheGameWasDeletedNeverRecreatesItsFolder() = runBlocking {
        val game = managedGame()
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(stage("script.rpy" to "patched\n"))) {}
        // A snapshot the UI could still be holding when the game is deleted.
        val stale = PatchTransactionStore.find(context, game.id)!!

        assertTrue(deleteManagedGameFilesAndRecord(context, game.toInstalledApp()))
        assertFalse(storageRoot.exists())

        // The delete already resolved the record, so the stale snapshot resolves to nothing at all.
        val outcome = PatchInstallTransaction.rollback(context, stale)
        val gone = outcome as PatchRollbackOutcome.NoLongerAvailable
        assertTrue(gone.message.contains("already resolved"))

        // Not a single directory or file was recreated, and no ghost record was resurrected.
        assertFalse(storageRoot.exists())
        assertFalse(gameFolder.exists())
        assertFalse(File(gameFolder, "script.rpy").exists())
        assertTrue(PatchTransactionStore.load(context).isEmpty())
    }

    @Test
    fun rollingBackAGameDeletedBehindTheJournalRefusesAndRemembersThePhase() = runBlocking {
        val game = managedGame()
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(stage("script.rpy" to "patched\n"))) {}
        val record = PatchTransactionStore.find(context, game.id)!!
        // The library entry disappears without the journal being told: the record survives, so the
        // rollback must refuse on the block reason instead of on a missing record.
        assertTrue(ManagedGameStore(context, patchJournal = ManagedGamePatchJournal.None).delete(game.id))
        assertTrue(storageRoot.deleteRecursively())

        val unresolved = PatchInstallTransaction.rollback(context, record) as PatchRollbackOutcome.Unresolved
        assertTrue(unresolved.message.contains("no longer in the AGM library"))
        assertFalse(storageRoot.exists())

        val kept = PatchTransactionStore.load(context).single()
        assertEquals(PatchTransactionPhase.Orphaned, kept.phase)
        assertEquals(PatchTransactionPhase.Committed, kept.orphanedFrom)
        assertFalse(kept.isUsableRollbackPoint)
    }

    @Test
    fun rollingBackARecordThatWasDiscardedFirstChangesNothingAtAll() = runBlocking {
        val game = managedGame()
        val staged = stage("script.rpy" to "patched script\n", "gallery.rpy" to "gallery\n")
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(staged)) {}
        // The list dialog is open and holds this snapshot while the record is discarded elsewhere.
        val stale = PatchTransactionStore.find(context, game.id)!!
        assertTrue(PatchInstallTransaction.discardRollbackPoint(context, stale))
        assertTrue(PatchTransactionStore.load(context).isEmpty())
        assertFalse(File(stale.workDir).exists())

        val outcome = PatchInstallTransaction.rollback(context, stale)
        assertTrue(outcome is PatchRollbackOutcome.NoLongerAvailable)

        // No journal entry, no work folder, and not one byte of the game changed.
        assertTrue(PatchTransactionStore.load(context).isEmpty())
        assertFalse(File(stale.workDir).exists())
        assertFalse(File(stale.backupDir).exists())
        assertTrue(library.listFiles()!!.none { it.name.startsWith(".agm-patch-") })
        assertEquals("patched script\n", File(gameFolder, "script.rpy").readText())
        assertEquals("gallery\n", File(gameFolder, "gallery.rpy").readText())
        assertEquals("define config.version = \"0.2\"\n", File(gameFolder, "options.rpy").readText())
        assertTrue(gameFolder.listFiles()!!.none { it.name.endsWith(".agm-patch-part") })
    }

    @Test
    fun rollingBackARecordThatWasForgottenFirstChangesNothingAtAll() = runBlocking {
        val game = managedGame()
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(stage("script.rpy" to "patched\n"))) {}
        val committed = PatchTransactionStore.find(context, game.id)!!
        // Only an invalidated record may be forgotten: its backup was already abandoned.
        val stale = committed.markOrphaned("the game was deleted").copy(invalidated = true)
        PatchTransactionStore.save(context, stale)
        val forgotten = PatchInstallTransaction.forgetRecord(context, stale)
        assertTrue(forgotten is PatchForgetOutcome.Forgotten)
        assertTrue(PatchTransactionStore.load(context).isEmpty())

        val outcome = PatchInstallTransaction.rollback(context, stale)
        assertTrue(outcome is PatchRollbackOutcome.NoLongerAvailable)
        assertTrue(PatchTransactionStore.load(context).isEmpty())
        assertFalse(File(stale.workDir).exists())
        assertEquals("patched\n", File(gameFolder, "script.rpy").readText())
    }

    @Test
    fun startupRecoveryOfADeletedGameNeverRecreatesItsFolder() = runBlocking {
        val game = managedGame()
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(stage("script.rpy" to "patched\n"))) {}
        val record = PatchTransactionStore.find(context, game.id)!!
        // The journal survived, but the library entry and the folder did not: the exact state a
        // crash between the two stores would leave behind.
        PatchTransactionStore.save(context, record.copy(phase = PatchTransactionPhase.Committing))
        assertTrue(ManagedGameStore(context, patchJournal = ManagedGamePatchJournal.None).delete(game.id))
        assertTrue(storageRoot.deleteRecursively())

        val outcomes = recoverPendingPatchInstalls(context)
        val unresolved = outcomes.single() as PatchRecoveryOutcome.Unresolved
        assertTrue(unresolved.message.contains("no longer in the AGM library"))
        assertFalse(storageRoot.exists())
        assertFalse(gameFolder.exists())

        val kept = PatchTransactionStore.load(context).single()
        assertEquals(PatchTransactionPhase.Orphaned, kept.phase)
        assertNotNull(kept.unresolvedReason)
        assertFalse(kept.isUsableRollbackPoint)
    }

    @Test
    fun relocatingTheGameDiscardsTheRollbackPointOfTheOldFolder() = runBlocking {
        val game = managedGame()
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(stage("script.rpy" to "patched\n"))) {}
        val record = PatchTransactionStore.find(context, game.id)!!
        val newRoot = relocationTarget()

        ManagedGameStore(context).relocate(game.canonicalPath, replacementFor(game, newRoot))

        assertTrue(PatchTransactionStore.load(context).isEmpty())
        assertFalse(File(record.workDir).exists())
    }

    @Test
    fun rollingBackAfterARelocationTouchesNeitherFolder() = runBlocking {
        val game = managedGame()
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(stage("script.rpy" to "patched\n"))) {}
        val stale = PatchTransactionStore.find(context, game.id)!!
        val newRoot = relocationTarget()
        val newGameFolder = File(newRoot, "game")
        File(newGameFolder, "script.rpy").writeText("upgraded script\n")
        assertTrue(storageRoot.deleteRecursively())

        ManagedGameStore(context).relocate(game.canonicalPath, replacementFor(game, newRoot))

        // The relocation already resolved the record, so the stale snapshot has nothing to act on.
        val outcome = PatchInstallTransaction.rollback(context, stale)
        assertTrue(outcome is PatchRollbackOutcome.NoLongerAvailable)

        // The old folder is not recreated and the upgraded folder is not written into.
        assertFalse(storageRoot.exists())
        assertFalse(gameFolder.exists())
        assertEquals("upgraded script\n", File(newGameFolder, "script.rpy").readText())
        assertEquals(listOf("script.rpy"), newGameFolder.list()!!.sorted())
        assertTrue(PatchTransactionStore.load(context).isEmpty())
    }

    @Test
    fun rollingBackAfterARelocationBehindTheJournalRefusesAndTouchesNeitherFolder() = runBlocking {
        val game = managedGame()
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(stage("script.rpy" to "patched\n"))) {}
        val stale = PatchTransactionStore.find(context, game.id)!!
        val newRoot = relocationTarget()
        val newGameFolder = File(newRoot, "game")
        File(newGameFolder, "script.rpy").writeText("upgraded script\n")
        assertTrue(storageRoot.deleteRecursively())

        // The journal is not told, so the record survives and the guard must refuse on its own.
        ManagedGameStore(context, patchJournal = ManagedGamePatchJournal.None)
            .relocate(game.canonicalPath, replacementFor(game, newRoot))

        val unresolved = PatchInstallTransaction.rollback(context, stale) as PatchRollbackOutcome.Unresolved
        assertTrue(unresolved.message.contains(newRoot.absolutePath))

        assertFalse(storageRoot.exists())
        assertEquals("upgraded script\n", File(newGameFolder, "script.rpy").readText())
        assertEquals(listOf("script.rpy"), newGameFolder.list()!!.sorted())
        val kept = PatchTransactionStore.load(context).single()
        assertEquals(PatchTransactionPhase.Orphaned, kept.phase)
        assertEquals(PatchTransactionPhase.Committed, kept.orphanedFrom)
    }

    @Test
    fun anUnresolvedTransactionBlocksDeleteAndRelocate() = runBlocking {
        val game = managedGame()
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(stage("script.rpy" to "patched\n"))) {}
        val record = PatchTransactionStore.find(context, game.id)!!
        PatchTransactionStore.save(context, record.copy(phase = PatchTransactionPhase.RollingBack))

        val store = ManagedGameStore(context)
        val deletion = runCatching { store.delete(game.id) }
        assertTrue(deletion.exceptionOrNull() is ManagedGamePatchTransactionException)
        assertTrue(deletion.exceptionOrNull()!!.message!!.contains("unresolved patch transaction"))

        val relocation = runCatching {
            store.relocate(game.canonicalPath, replacementFor(game, relocationTarget()))
        }
        assertTrue(relocation.exceptionOrNull() is ManagedGamePatchTransactionException)

        // Files-and-record deletion refuses before it removes a single file.
        val fileDeletion = runCatching { deleteManagedGameFilesAndRecord(context, game.toInstalledApp()) }
        assertTrue(fileDeletion.exceptionOrNull() is ManagedGamePatchTransactionException)
        assertTrue(storageRoot.isDirectory)
        assertEquals("patched\n", File(gameFolder, "script.rpy").readText())
        assertNotNull(ManagedGameStore(context).find(game.id))
        assertEquals(game.canonicalPath, ManagedGameStore(context).find(game.id)!!.canonicalPath)
    }

    @Test
    fun aBackupThatCannotBeDeletedIsKeptAsAnInvalidRecord() = runBlocking {
        val game = managedGame()
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(stage("script.rpy" to "patched\n"))) {}
        val record = PatchTransactionStore.find(context, game.id)!!

        // The work folder lives next to the game folder; a read-only library folder makes its
        // removal fail exactly the way a permission problem would.
        assertTrue(library.setWritable(false))
        try {
            assertTrue(ManagedGameStore(context).delete(game.id))
        } finally {
            library.setWritable(true)
        }

        val kept = PatchTransactionStore.load(context).single()
        assertTrue(kept.invalidated)
        assertEquals(PatchTransactionPhase.Orphaned, kept.phase)
        assertFalse(kept.isUsableRollbackPoint)
        assertTrue(kept.unresolvedReason!!.contains(record.workDir))

        // It can never be restored, and it never blocks a future patch of another record.
        val outcome = PatchInstallTransaction.rollback(context, kept)
        assertTrue(outcome is PatchRollbackOutcome.Unresolved)
        assertEquals("patched\n", File(gameFolder, "script.rpy").readText())
        assertNull(PatchTransactionStore.find(context, game.id))

        // Forgetting it removes the record without touching the installed game.
        val forgotten = PatchInstallTransaction.forgetRecord(context, kept)
        assertTrue(forgotten is PatchForgetOutcome.Forgotten)
        assertTrue(PatchTransactionStore.load(context).isEmpty())
        assertEquals("patched\n", File(gameFolder, "script.rpy").readText())
    }

    @Test
    fun reconciliationDemotesAndRestoresRollbackPointsWithTheLibrary() = runBlocking {
        val game = managedGame()
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(stage("script.rpy" to "patched\n"))) {}
        val record = PatchTransactionStore.find(context, game.id)!!

        // The patched folder disappears: the record must stop being offered as a rollback point.
        val hidden = File(workspace, "hidden-game")
        assertTrue(gameFolder.renameTo(hidden))
        val demoted = PatchInstallTransaction.reconcileWithLibrary(context).single()
        assertEquals(PatchTransactionPhase.Orphaned, demoted.phase)
        assertEquals(PatchTransactionPhase.Committed, demoted.orphanedFrom)
        assertFalse(demoted.isUsableRollbackPoint)
        assertNotNull(demoted.unresolvedReason)

        // It comes back on its own once the library matches again.
        assertTrue(hidden.renameTo(gameFolder))
        val restored = PatchInstallTransaction.reconcileWithLibrary(context).single()
        assertEquals(PatchTransactionPhase.Committed, restored.phase)
        assertNull(restored.orphanedFrom)
        assertTrue(restored.isUsableRollbackPoint)
        assertNull(restored.unresolvedReason)
        assertEquals(record.transactionId, restored.transactionId)
    }

    @Test
    fun anOrphanedInterruptedCommitIsRestoredToCommittingAndThenRolledBack() = runBlocking {
        val game = managedGame()
        val staged = stage("script.rpy" to "patched script\n", "gallery.rpy" to "gallery\n")
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(staged)) {}
        val committed = PatchTransactionStore.find(context, game.id)!!
        PatchTransactionStore.save(context, committed.copy(phase = PatchTransactionPhase.Committing))

        // The patched folder is temporarily unreachable, so recovery can only orphan the record.
        val hidden = File(workspace, "hidden-committing")
        assertTrue(gameFolder.renameTo(hidden))
        assertTrue(recoverPendingPatchInstalls(context).single() is PatchRecoveryOutcome.Unresolved)
        val orphaned = PatchTransactionStore.load(context).single()
        assertEquals(PatchTransactionPhase.Orphaned, orphaned.phase)
        assertEquals(PatchTransactionPhase.Committing, orphaned.orphanedFrom)
        assertFalse(orphaned.isUsableRollbackPoint)

        // Storage returns: an interrupted commit must never be promoted to a rollback point.
        assertTrue(hidden.renameTo(gameFolder))
        val restored = PatchInstallTransaction.reconcileWithLibrary(context).single()
        assertEquals(PatchTransactionPhase.Committing, restored.phase)
        assertNull(restored.orphanedFrom)
        assertNull(restored.unresolvedReason)
        assertFalse(restored.isUsableRollbackPoint)

        // Startup recovery resumes and finishes the interrupted commit by rolling it back.
        assertTrue(recoverPendingPatchInstalls(context).single() is PatchRecoveryOutcome.RolledBack)
        assertEquals("original script\n", File(gameFolder, "script.rpy").readText())
        assertFalse(File(gameFolder, "gallery.rpy").exists())
        assertTrue(PatchTransactionStore.load(context).isEmpty())
    }

    @Test
    fun anOrphanedInterruptedRollbackIsRestoredToRollingBackAndThenFinished() = runBlocking {
        val game = managedGame()
        val staged = stage("script.rpy" to "patched script\n", "gallery.rpy" to "gallery\n")
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(staged)) {}
        val committed = PatchTransactionStore.find(context, game.id)!!
        PatchTransactionStore.save(context, committed.copy(phase = PatchTransactionPhase.RollingBack))

        val hidden = File(workspace, "hidden-rollingback")
        assertTrue(gameFolder.renameTo(hidden))
        assertTrue(recoverPendingPatchInstalls(context).single() is PatchRecoveryOutcome.Unresolved)
        val orphaned = PatchTransactionStore.load(context).single()
        assertEquals(PatchTransactionPhase.Orphaned, orphaned.phase)
        assertEquals(PatchTransactionPhase.RollingBack, orphaned.orphanedFrom)

        assertTrue(hidden.renameTo(gameFolder))
        val restored = PatchInstallTransaction.reconcileWithLibrary(context).single()
        assertEquals(PatchTransactionPhase.RollingBack, restored.phase)
        assertNull(restored.orphanedFrom)
        assertFalse(restored.isUsableRollbackPoint)

        assertTrue(recoverPendingPatchInstalls(context).single() is PatchRecoveryOutcome.RolledBack)
        assertEquals("original script\n", File(gameFolder, "script.rpy").readText())
        assertFalse(File(gameFolder, "gallery.rpy").exists())
        assertTrue(PatchTransactionStore.load(context).isEmpty())
    }

    @Test
    fun aLegacyOrphanedRecordIsNeitherPromotedNorRolledBack() = runBlocking {
        val game = managedGame()
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(stage("script.rpy" to "patched\n"))) {}
        val committed = PatchTransactionStore.find(context, game.id)!!
        // Exactly what a journal written before the provenance field existed holds.
        PatchTransactionStore.save(
            context,
            committed.copy(
                phase = PatchTransactionPhase.Orphaned,
                orphanedFrom = null,
                unresolvedReason = "the library could not be read",
            ),
        )

        // Everything matches the library again, but the record's real phase is unknown.
        val reconciled = PatchInstallTransaction.reconcileWithLibrary(context).single()
        assertEquals(PatchTransactionPhase.Orphaned, reconciled.phase)
        assertNull(reconciled.orphanedFrom)
        assertFalse(reconciled.isUsableRollbackPoint)

        // Recovery reports it and refuses to undo a patch it cannot prove was interrupted.
        val outcome = recoverPendingPatchInstalls(context).single() as PatchRecoveryOutcome.Unresolved
        assertTrue(outcome.message.contains("the library could not be read"))
        assertEquals("patched\n", File(gameFolder, "script.rpy").readText())
        assertTrue(File(committed.backupDir, "script.rpy").isFile)
        assertEquals(PatchTransactionPhase.Orphaned, PatchTransactionStore.load(context).single().phase)
    }

    @Test
    fun noInterruptedOrOrphanedRecordCanBeForgottenAndItsBackupSurvives() = runBlocking {
        val game = managedGame()
        val staged = stage("script.rpy" to "patched script\n", "gallery.rpy" to "gallery\n")
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(staged)) {}
        val committed = PatchTransactionStore.find(context, game.id)!!

        val protected = listOf(
            committed.copy(phase = PatchTransactionPhase.Committing),
            committed.copy(phase = PatchTransactionPhase.RollingBack),
            committed.copy(phase = PatchTransactionPhase.Committing).markOrphaned("storage vanished"),
            committed.copy(phase = PatchTransactionPhase.RollingBack).markOrphaned("storage vanished"),
            committed.markOrphaned("the library could not be read"),
            committed.copy(phase = PatchTransactionPhase.Orphaned, orphanedFrom = null),
        )
        for (record in protected) {
            PatchTransactionStore.save(context, record)
            val outcome = PatchInstallTransaction.forgetRecord(context, record)
            assertTrue(
                "${record.phase} (from ${record.orphanedFrom}) was forgotten",
                outcome is PatchForgetOutcome.Refused,
            )

            // The journal entry, the backup and the game are all exactly as they were.
            val kept = PatchTransactionStore.load(context).single()
            assertEquals(record.phase, kept.phase)
            assertEquals(record.orphanedFrom, kept.orphanedFrom)
            assertTrue(File(kept.workDir).isDirectory)
            assertEquals("original script\n", File(kept.backupDir, "script.rpy").readText())
            assertEquals("patched script\n", File(gameFolder, "script.rpy").readText())
            assertEquals("gallery\n", File(gameFolder, "gallery.rpy").readText())
        }

        // A stale caller snapshot claiming a forgettable state never gets past the re-read.
        PatchTransactionStore.save(context, committed.copy(phase = PatchTransactionPhase.Committing))
        val lying = committed.copy(phase = PatchTransactionPhase.Orphaned, invalidated = true)
        assertTrue(PatchInstallTransaction.forgetRecord(context, lying) is PatchForgetOutcome.Refused)
        assertEquals(1, PatchTransactionStore.load(context).size)
        assertEquals("original script\n", File(committed.backupDir, "script.rpy").readText())
    }

    @Test
    fun retryingARollbackKeepsEverythingWhileBlockedAndFinishesWhenTheGameIsBack() = runBlocking {
        val game = managedGame()
        val staged = stage("script.rpy" to "patched script\n", "gallery.rpy" to "gallery\n")
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(staged)) {}
        val committed = PatchTransactionStore.find(context, game.id)!!
        // The exact state a crash mid-commit leaves behind.
        PatchTransactionStore.save(context, committed.copy(phase = PatchTransactionPhase.Committing))

        val hidden = File(workspace, "hidden-retry")
        assertTrue(gameFolder.renameTo(hidden))

        val blocked = PatchInstallTransaction
            .retryRollback(context, committed.transactionId) as PatchRollbackOutcome.Unresolved
        assertTrue(blocked.message.contains("does not exist any more"))

        // Blocked retry: journal, provenance and backup all survive, and forgetting is still refused.
        val kept = PatchTransactionStore.load(context).single()
        assertEquals(PatchTransactionPhase.Orphaned, kept.phase)
        assertEquals(PatchTransactionPhase.Committing, kept.orphanedFrom)
        assertNotNull(kept.unresolvedReason)
        assertEquals("original script\n", File(kept.backupDir, "script.rpy").readText())
        assertTrue(PatchInstallTransaction.forgetRecord(context, kept) is PatchForgetOutcome.Refused)
        assertEquals(1, PatchTransactionStore.load(context).size)
        assertTrue(File(kept.workDir).isDirectory)

        // The destination is valid again: the retry resumes from the persisted record and finishes.
        assertTrue(hidden.renameTo(gameFolder))
        val done = PatchInstallTransaction
            .retryRollback(context, committed.transactionId) as PatchRollbackOutcome.RolledBack
        assertEquals(1, done.restoredCount)
        assertEquals("original script\n", File(gameFolder, "script.rpy").readText())
        assertFalse(File(gameFolder, "gallery.rpy").exists())
        assertTrue(gameFolder.listFiles()!!.none { it.name.endsWith(".agm-patch-part") })
        assertTrue(PatchTransactionStore.load(context).isEmpty())
        assertFalse(File(committed.workDir).exists())
    }

    @Test
    fun retryingAResolvedRecordResurrectsNothing() = runBlocking {
        val game = managedGame()
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(stage("script.rpy" to "patched\n"))) {}
        val committed = PatchTransactionStore.find(context, game.id)!!
        assertTrue(PatchInstallTransaction.discardRollbackPoint(context, committed))

        val outcome = PatchInstallTransaction.retryRollback(context, committed.transactionId)
        assertTrue(outcome is PatchRollbackOutcome.NoLongerAvailable)
        assertTrue(PatchTransactionStore.load(context).isEmpty())
        assertEquals("patched\n", File(gameFolder, "script.rpy").readText())
    }

    @Test
    fun anInvalidatedRecordCannotBeRetriedButIsExplicitCleanup() = runBlocking {
        val game = managedGame()
        PatchInstallTransaction.install(context, game, "patch.zip", staging, plan(stage("script.rpy" to "patched\n"))) {}
        val committed = PatchTransactionStore.find(context, game.id)!!
        val invalidated = committed
            .markOrphaned("the game was deleted, so AGM deleted the patch backup.")
            .copy(invalidated = true)
        PatchTransactionStore.save(context, invalidated)

        val refused = PatchInstallTransaction
            .retryRollback(context, committed.transactionId) as PatchRollbackOutcome.Unresolved
        assertTrue(refused.message.contains("the game was deleted"))
        assertEquals("patched\n", File(gameFolder, "script.rpy").readText())
        assertEquals(1, PatchTransactionStore.load(context).size)

        assertTrue(invalidated.isSafeToForget)
        val forgotten = PatchInstallTransaction
            .forgetRecord(context, invalidated) as PatchForgetOutcome.Forgotten
        assertTrue(forgotten.workDirDeleted)
        assertTrue(PatchTransactionStore.load(context).isEmpty())
        assertFalse(File(committed.workDir).exists())
        assertEquals("patched\n", File(gameFolder, "script.rpy").readText())
    }
}
