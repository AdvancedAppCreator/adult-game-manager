package com.example.f95updater

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * The filesystem half of the patch installer.
 *
 * Ordering is fixed and never relaxed: stage -> verify staged content -> back up every file that
 * will be replaced -> verify the backups -> write the durable journal -> mutate the destination ->
 * verify every written file. Any failure after the journal exists triggers a rollback that restores
 * the originals and removes the files the patch added. When a rollback cannot be verified, the
 * journal and the backup folder are deliberately kept so the next app start can retry or report.
 * Any failure *before* the journal exists deletes that transaction's work folder instead: nothing
 * outside it was touched and no recovery pass could ever find it, so leaving it behind would only
 * accumulate orphaned backups in the library folder.
 */

private const val PATCH_PART_SUFFIX = ".agm-patch-part"
private const val PATCH_WORK_PREFIX = ".agm-patch-"
private const val PATCH_FREE_SPACE_RESERVE = 64L * 1024L * 1024L
private const val PATCH_COPY_BUFFER = 64 * 1024

data class PatchInstallPlan(
    val destinationRoot: File,
    val additions: List<PatchStagedFile>,
    val replacements: List<PatchStagedFile>,
    val createdDirectories: List<String>,
) {
    val totalBytes: Long get() = additions.sumOf { it.size } + replacements.sumOf { it.size }
    val fileCount: Int get() = additions.size + replacements.size
}

sealed interface PatchPlanOutcome {
    data class Rejected(val reason: String) : PatchPlanOutcome
    data class Ready(val plan: PatchInstallPlan) : PatchPlanOutcome
}

object PatchInstallPlanner {

    fun plan(destinationRoot: File, staged: List<PatchStagedFile>): PatchPlanOutcome {
        if (!destinationRoot.isDirectory) {
            return PatchPlanOutcome.Rejected(
                "The patch destination ${destinationRoot.absolutePath} does not exist.",
            )
        }
        if (!destinationRoot.canWrite()) {
            return PatchPlanOutcome.Rejected(
                "AGM cannot write to ${destinationRoot.absolutePath}.",
            )
        }
        val rootCanonical = runCatching { destinationRoot.canonicalFile }.getOrNull()
            ?: return PatchPlanOutcome.Rejected(
                "AGM cannot resolve ${destinationRoot.absolutePath}.",
            )

        val additions = mutableListOf<PatchStagedFile>()
        val replacements = mutableListOf<PatchStagedFile>()
        val createdDirectories = LinkedHashSet<String>()
        for (file in staged.sortedBy { it.relativePath }) {
            val target = File(destinationRoot, file.relativePath)
            val targetCanonical = runCatching { target.canonicalFile }.getOrNull()
                ?: return PatchPlanOutcome.Rejected("AGM cannot resolve ${target.absolutePath}.")
            if (!isInside(rootCanonical, targetCanonical)) {
                return PatchPlanOutcome.Rejected(
                    "'${file.relativePath}' would be written outside ${destinationRoot.absolutePath}.",
                )
            }
            if (target.isDirectory) {
                return PatchPlanOutcome.Rejected(
                    "'${file.relativePath}' already exists as a folder in the installed game.",
                )
            }
            val segments = file.relativePath.split('/')
            var cursorRelative = ""
            for (index in 0 until segments.lastIndex) {
                cursorRelative = if (cursorRelative.isEmpty()) segments[index] else "$cursorRelative/${segments[index]}"
                val directory = File(destinationRoot, cursorRelative)
                if (directory.exists()) {
                    if (!directory.isDirectory) {
                        return PatchPlanOutcome.Rejected(
                            "'$cursorRelative' exists as a file in the installed game, so " +
                                "'${file.relativePath}' cannot be created.",
                        )
                    }
                    val directoryCanonical = runCatching { directory.canonicalFile }.getOrNull()
                        ?: return PatchPlanOutcome.Rejected("AGM cannot resolve ${directory.absolutePath}.")
                    if (!isInside(rootCanonical, directoryCanonical)) {
                        return PatchPlanOutcome.Rejected(
                            "'$cursorRelative' resolves outside ${destinationRoot.absolutePath}.",
                        )
                    }
                } else {
                    createdDirectories += cursorRelative
                }
            }
            if (target.isFile) replacements += file else additions += file
        }
        if (additions.isEmpty() && replacements.isEmpty()) {
            return PatchPlanOutcome.Rejected("The patch would not change any file.")
        }

        val plan = PatchInstallPlan(
            destinationRoot = destinationRoot,
            additions = additions,
            replacements = replacements,
            createdDirectories = createdDirectories.sorted(),
        )
        val backupBytes = replacements.sumOf { File(destinationRoot, it.relativePath).length() }
        val required = plan.totalBytes + backupBytes + PATCH_FREE_SPACE_RESERVE
        val available = destinationRoot.usableSpace
        if (available in 1 until required) {
            return PatchPlanOutcome.Rejected(
                "The patch needs about $required bytes including a full backup, but only " +
                    "$available bytes are free where the game is installed.",
            )
        }
        return PatchPlanOutcome.Ready(plan)
    }

    private fun isInside(root: File, candidate: File): Boolean = isInsidePatchDirectory(root, candidate)
}

data class PatchInstallResult(
    val transactionId: String,
    val managedGameId: String,
    val label: String,
    val storagePath: String,
    val destinationRoot: String,
    val archiveName: String,
    val addedCount: Int,
    val replacedCount: Int,
    /** True when this install ran through an explicit, acknowledged compatibility override. */
    val compatibilityOverridden: Boolean = false,
    val compatibilityOverrideReason: String? = null,
)

sealed interface PatchRollbackOutcome {
    data class RolledBack(
        val label: String,
        val storagePath: String,
        val restoredCount: Int,
        val removedCount: Int,
    ) : PatchRollbackOutcome

    data class Unresolved(val label: String, val message: String) : PatchRollbackOutcome

    /**
     * The record was already resolved (rolled back, discarded, forgotten) before this rollback got
     * the lock. Nothing was read, written or journalled: a vanished record is never resurrected.
     */
    data class NoLongerAvailable(val label: String, val message: String) : PatchRollbackOutcome
}

sealed interface PatchRecoveryOutcome {
    data class RolledBack(val label: String, val message: String) : PatchRecoveryOutcome
    data class Unresolved(val label: String, val message: String) : PatchRecoveryOutcome
}

/** Result of asking AGM to drop a patch record and its private work folder. */
sealed interface PatchForgetOutcome {
    /** The record was safe to drop. [workDirDeleted] is false when the folder survived. */
    data class Forgotten(
        val label: String,
        val workDir: String,
        val workDirDeleted: Boolean,
    ) : PatchForgetOutcome

    /**
     * The persisted record still needs its backup, so nothing was read, written or deleted. The
     * journal entry and the backup folder are exactly as they were.
     */
    data class Refused(val label: String, val reason: String) : PatchForgetOutcome

    /** The record had already been resolved elsewhere; nothing was touched. */
    data class AlreadyGone(val label: String) : PatchForgetOutcome
}

internal fun isInsidePatchDirectory(root: File, candidate: File): Boolean {
    val rootPath = root.path.trimEnd(File.separatorChar)
    val candidatePath = candidate.path
    return candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separatorChar)
}

/**
 * Decides whether [record] may still be applied to [game], the managed game the record claims to
 * belong to, re-read from [ManagedGameStore] at rollback time.
 *
 * This is pure and read-only: it never creates a directory and never touches a game file, so it is
 * safe to call before a rollback has changed a single byte. A rollback that trusted the recorded
 * paths could otherwise recreate a folder the user deleted, or write a stale backup into the folder
 * of a game that has since been upgraded and moved.
 */
internal fun patchRollbackBlockReason(
    record: PatchTransactionRecord,
    game: ManagedGame?,
): String? {
    if (record.invalidated) {
        return record.unresolvedReason
            ?: "${record.label}'s rollback point was invalidated when the game was removed or moved, " +
                "so AGM will not restore anything from it."
    }
    if (game == null) {
        return "${record.label} is no longer in the AGM library, so AGM will not recreate " +
            "${record.destinationRoot} or restore the patch backup into it. The backup in " +
            "${record.backupDir} was left untouched."
    }
    val recordedPath = runCatching { canonicalManagedGamePath(record.storagePath) }.getOrNull()
        ?: return "The rollback point for ${record.label} records an unusable folder " +
            "(${record.storagePath}), so AGM will not touch anything."
    val currentPath = runCatching { canonicalManagedGamePath(game.storagePath) }.getOrNull()
    if (currentPath != recordedPath || game.canonicalPath != recordedPath) {
        return "${record.label} is now installed in ${game.storagePath}, not ${record.storagePath}. " +
            "AGM will not restore a patch backup into a folder the game no longer uses, and it did " +
            "not change the current folder."
    }
    val storageRoot = File(game.storagePath)
    if (!storageRoot.isDirectory) {
        return "${record.label}'s folder ${game.storagePath} does not exist any more, so AGM will not " +
            "recreate it to roll the patch back."
    }
    val destination = File(record.destinationRoot)
    if (!destination.isDirectory) {
        return "The patched folder ${record.destinationRoot} does not exist any more, so AGM will not " +
            "recreate it to roll the patch back."
    }
    val storageCanonical = runCatching { storageRoot.canonicalFile }.getOrNull()
    val destinationCanonical = runCatching { destination.canonicalFile }.getOrNull()
    if (storageCanonical == null || destinationCanonical == null ||
        !isInsidePatchDirectory(storageCanonical, destinationCanonical)
    ) {
        return "The patched folder ${record.destinationRoot} is not inside ${game.storagePath} any " +
            "more, so AGM will not restore the patch backup into it."
    }
    return null
}

class PatchInstallException(message: String) : Exception(message)

object PatchInstallTransaction {

    private val mutex = Mutex()

    /**
     * Runs [block] with the process-wide patch lock held. Used by the managed-game store so a
     * delete or a relocation can never interleave with an install, a rollback or recovery.
     */
    internal suspend fun <T> withJournalLock(block: suspend () -> T): T = mutex.withLock {
        withContext(NonCancellable + Dispatchers.IO) { block() }
    }

    private suspend fun loadLibrary(context: Context): Result<Map<String, ManagedGame>> =
        runCatching { ManagedGameStore(context.applicationContext).get().associateBy { it.id } }
            .onFailure { AppLog.e("PatchInstall", "Could not read the managed-game library", it) }

    /**
     * Re-resolves the managed game named by [record] and returns why the record must not be applied,
     * or null when the record still matches the library exactly.
     */
    private fun blockReason(
        record: PatchTransactionRecord,
        library: Result<Map<String, ManagedGame>>,
    ): String? {
        val games = library.getOrElse { error ->
            return "AGM could not read its managed-game library " +
                "(${error.message ?: error::class.simpleName}), so it did not touch " +
                "${record.destinationRoot} for ${record.label}."
        }
        return patchRollbackBlockReason(record, games[record.managedGameId])
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(PATCH_COPY_BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun workDirectoryFor(storageRoot: File, transactionId: String): File {
        val parent = storageRoot.parentFile ?: storageRoot
        return File(parent, "$PATCH_WORK_PREFIX$transactionId")
    }

    /** The IO primitives of the pre-journal phase, named so a test can make each one fail. */
    internal enum class PatchInstallFaultPoint {
        /** Hashing a staged patch file during verification. */
        SourceHash,

        /** Hashing an installed file before it is backed up. */
        OriginalHash,

        /** Copying an installed file into the backup folder. */
        BackupCopy,

        /** Re-hashing the backup copy to prove it matches the original. */
        BackupHash,
    }

    /**
     * Test-only hook invoked immediately before each pre-journal IO primitive. Production leaves it
     * null. Tests set it to throw so the guard around the pre-journal phase can be proven to delete
     * the work folder for *unhandled* IO errors too, not just for the failures checked inline.
     */
    @VisibleForTesting
    internal var preJournalFault: ((PatchInstallFaultPoint) -> Unit)? = null

    private fun faultPoint(point: PatchInstallFaultPoint) {
        preJournalFault?.invoke(point)
    }

    /**
     * Runs the pre-journal phase of an install. Until the journal entry is persisted, [workDir] has
     * no owner but this call: nothing outside it has been touched and no recovery pass can find it.
     * Any escaping failure — a size mismatch, a vanished file, or an unexpected IO error from a
     * digest, a copy or the journal write — must therefore delete exactly this transaction's work
     * folder before it propagates. Once the journal is saved, recovery owns the folder instead and
     * this guard is no longer applied.
     */
    private inline fun <T> preJournal(workDir: File, block: () -> T): T =
        try {
            block()
        } catch (error: Throwable) {
            AppLog.w("PatchInstall", "Pre-journal patch failure; removing ${workDir.absolutePath}", error)
            cleanupWorkDirectory(workDir)
            throw error
        }

    /**
     * Installs [plan] from [stagingRoot] into the destination recorded in the plan. Runs under a
     * process-wide lock so two patches can never interleave on the same library.
     *
     * [compatibilityOverrideReason] is non-null only when the user explicitly acknowledged an
     * unproven or mismatched version. It changes nothing about how the install runs — the same
     * backups, journal, verification and rollback apply — it is only recorded so the installed
     * patches and rollback UI can say the compatibility was overridden and why.
     */
    suspend fun install(
        context: Context,
        game: ManagedGame,
        archiveName: String,
        stagingRoot: File,
        plan: PatchInstallPlan,
        compatibilityOverrideReason: String? = null,
        onProgress: (ArchiveExtractor.Progress) -> Unit,
    ): PatchInstallResult = mutex.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            installLocked(context, game, archiveName, stagingRoot, plan, compatibilityOverrideReason, onProgress)
        }
    }

    /**
     * Re-checks, under the transaction lock, that every file the plan classified as an addition is
     * still absent. A preview can be minutes old, and a destination that gained a file in the
     * meantime must never be silently overwritten: an addition is never promoted to a replacement,
     * because nothing backed that file up. Returns the refusal reason, or null when the plan still
     * matches the destination.
     */
    internal fun additionsChangedSincePreview(plan: PatchInstallPlan): String? {
        val appeared = plan.additions.filter { File(plan.destinationRoot, it.relativePath).exists() }
        if (appeared.isEmpty()) return null
        val names = appeared.map { it.relativePath }
        val listed = names.take(5).joinToString(", ") { "'$it'" }
        val extra = if (names.size > 5) " and ${names.size - 5} more" else ""
        return "The installed game changed since the patch was previewed: $listed$extra did not exist " +
            "when AGM planned this patch but exist(s) in the game now. AGM will not overwrite a file " +
            "it did not plan to replace and back up, so nothing was changed. Re-run the patch install " +
            "so it can be planned against the current game."
    }

    private suspend fun installLocked(
        context: Context,
        game: ManagedGame,
        archiveName: String,
        stagingRoot: File,
        plan: PatchInstallPlan,
        compatibilityOverrideReason: String?,
        onProgress: (ArchiveExtractor.Progress) -> Unit,
    ): PatchInstallResult {
        PatchTransactionStore.find(context, game.id)?.let {
            throw PatchInstallException(
                "${game.label} already has patch transaction ${it.transactionId} (${it.phase}). " +
                    "Resolve it before installing another patch.",
            )
        }

        // Nothing has been created or journalled yet, so a stale plan can still be refused cleanly.
        additionsChangedSincePreview(plan)?.let { throw PatchInstallException(it) }

        val transactionId = UUID.randomUUID().toString()
        val storageRoot = File(game.storagePath)
        val workDir = workDirectoryFor(storageRoot, transactionId)
        val backupDir = File(workDir, "backup")
        if (!backupDir.mkdirs()) {
            throw PatchInstallException("AGM could not create its patch work folder ${workDir.absolutePath}.")
        }

        val staged = plan.additions + plan.replacements
        val totalBytes = plan.totalBytes
        var bytesDone = 0L
        var index = 0
        val stagedDigests = LinkedHashMap<String, String>()

        var record = preJournal(workDir) {
            // 1. Verify every staged file against its declared size and its own re-read digest.
            for (file in staged) {
                index++
                val source = File(stagingRoot, file.relativePath)
                if (!source.isFile) {
                    throw PatchInstallException("The staged patch is missing '${file.relativePath}'.")
                }
                if (source.length() != file.size) {
                    throw PatchInstallException(
                        "Staged file '${file.relativePath}' is ${source.length()} bytes but the archive " +
                            "declared ${file.size}.",
                    )
                }
                faultPoint(PatchInstallFaultPoint.SourceHash)
                val digest = sha256(source)
                stagedDigests[file.relativePath] = digest
                bytesDone += file.size
                onProgress(
                    ArchiveExtractor.Progress(
                        bytesDone, totalBytes, index, staged.size,
                        "Verifying ${file.relativePath}",
                    ),
                )
            }

            // 2. Back up every file the patch replaces, and prove the backup matches the original.
            val replaced = mutableListOf<PatchReplacedFile>()
            index = 0
            for (file in plan.replacements) {
                index++
                val original = File(plan.destinationRoot, file.relativePath)
                if (!original.isFile) {
                    throw PatchInstallException(
                        "'${file.relativePath}' disappeared from the installed game before the backup ran.",
                    )
                }
                faultPoint(PatchInstallFaultPoint.OriginalHash)
                val originalDigest = sha256(original)
                val originalSize = original.length()
                val backup = File(backupDir, file.relativePath)
                backup.parentFile?.mkdirs()
                faultPoint(PatchInstallFaultPoint.BackupCopy)
                copyFile(original, backup)
                faultPoint(PatchInstallFaultPoint.BackupHash)
                if (backup.length() != originalSize || sha256(backup) != originalDigest) {
                    throw PatchInstallException("AGM could not make a verified backup of '${file.relativePath}'.")
                }
                replaced += PatchReplacedFile(file.relativePath, originalDigest, originalSize)
                onProgress(
                    ArchiveExtractor.Progress(
                        bytesDone, totalBytes, index, plan.replacements.size,
                        "Backing up ${file.relativePath}",
                    ),
                )
            }

            // 3. Journal before the first destination mutation.
            val pending = PatchTransactionRecord(
                transactionId = transactionId,
                phase = PatchTransactionPhase.Committing,
                managedGameId = game.id,
                label = game.label,
                storagePath = game.storagePath,
                destinationRoot = plan.destinationRoot.absolutePath,
                workDir = workDir.absolutePath,
                backupDir = backupDir.absolutePath,
                archiveName = archiveName,
                added = plan.additions.map {
                    PatchAddedFile(it.relativePath, stagedDigests.getValue(it.relativePath), it.size)
                },
                replaced = replaced,
                createdDirectories = plan.createdDirectories,
                compatibilityOverridden = compatibilityOverrideReason != null,
                compatibilityOverrideReason = compatibilityOverrideReason,
            )
            try {
                PatchTransactionStore.save(context, pending)
            } catch (error: Throwable) {
                throw PatchInstallException(
                    "AGM could not write its patch recovery journal, so nothing was changed: ${error.message}",
                )
            }
            pending
        }
        AppLog.i(
            "PatchInstall",
            "Committing patch tx=$transactionId game=${game.id} dest=${plan.destinationRoot.absolutePath} " +
                "add=${plan.additions.size} replace=${plan.replacements.size} " +
                "compatibilityOverridden=${compatibilityOverrideReason != null}",
        )

        // 4. Mutate, then verify.
        try {
            plan.createdDirectories.forEach { relative ->
                val directory = File(plan.destinationRoot, relative)
                if (!directory.isDirectory && !directory.mkdirs()) {
                    throw PatchInstallException("AGM could not create '$relative' in the installed game.")
                }
            }
            index = 0
            bytesDone = 0L
            for (file in staged) {
                index++
                val source = File(stagingRoot, file.relativePath)
                val target = File(plan.destinationRoot, file.relativePath)
                val part = File(target.parentFile, target.name + PATCH_PART_SUFFIX)
                copyFile(source, part)
                val expected = stagedDigests.getValue(file.relativePath)
                if (part.length() != file.size || sha256(part) != expected) {
                    part.delete()
                    throw PatchInstallException("AGM could not write a verified copy of '${file.relativePath}'.")
                }
                if (target.exists() && !target.delete()) {
                    part.delete()
                    throw PatchInstallException("AGM could not replace '${file.relativePath}'.")
                }
                if (!part.renameTo(target)) {
                    part.delete()
                    throw PatchInstallException("AGM could not move '${file.relativePath}' into place.")
                }
                bytesDone += file.size
                onProgress(
                    ArchiveExtractor.Progress(
                        bytesDone, totalBytes, index, staged.size,
                        "Installing ${file.relativePath}",
                    ),
                )
            }
            for (file in staged) {
                val target = File(plan.destinationRoot, file.relativePath)
                val expected = stagedDigests.getValue(file.relativePath)
                if (!target.isFile || target.length() != file.size || sha256(target) != expected) {
                    throw PatchInstallException(
                        "'${file.relativePath}' does not match the patch after installation.",
                    )
                }
            }
        } catch (error: Throwable) {
            AppLog.e("PatchInstall", "Patch commit failed for ${game.label}; rolling back", error)
            val outcome = rollbackLocked(context, record, onProgress)
            val detail = error.message ?: error::class.simpleName
            throw when (outcome) {
                is PatchRollbackOutcome.RolledBack -> PatchInstallException(
                    "The patch could not be installed ($detail). ${game.label} was restored to its " +
                        "previous state.",
                )
                is PatchRollbackOutcome.Unresolved -> PatchInstallException(
                    "The patch could not be installed ($detail) and the rollback did not finish: " +
                        "${outcome.message}",
                )
                is PatchRollbackOutcome.NoLongerAvailable -> PatchInstallException(
                    "The patch could not be installed ($detail) and its recovery journal entry " +
                        "disappeared before the rollback could run: ${outcome.message}",
                )
            }
        }

        record = record.copy(phase = PatchTransactionPhase.Committed)
        PatchTransactionStore.save(context, record)
        AppLog.i("PatchInstall", "Patch committed tx=$transactionId game=${game.id}")
        return PatchInstallResult(
            transactionId = transactionId,
            managedGameId = game.id,
            label = game.label,
            storagePath = game.storagePath,
            destinationRoot = plan.destinationRoot.absolutePath,
            archiveName = archiveName,
            addedCount = plan.additions.size,
            replacedCount = plan.replacements.size,
            compatibilityOverridden = compatibilityOverrideReason != null,
            compatibilityOverrideReason = compatibilityOverrideReason,
        )
    }

    suspend fun rollback(
        context: Context,
        record: PatchTransactionRecord,
        onProgress: (ArchiveExtractor.Progress) -> Unit = {},
    ): PatchRollbackOutcome = mutex.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            rollbackLocked(context, record, onProgress)
        }
    }

    /**
     * Startup-recovery entry point. The caller's snapshot can be stale by the time the transaction
     * lock is free, so the record is re-read by [PatchTransactionRecord.transactionId] under the
     * lock and only rolled back when the *persisted* record is still an interrupted mutation.
     * Returns null when the record is gone, has since become [PatchTransactionPhase.Committed], or
     * is orphaned from a state recovery must not undo on its own; a committed record is the user's
     * rollback point and is never touched by recovery.
     */
    suspend fun rollbackIfStillUnresolved(
        context: Context,
        transactionId: String,
        onProgress: (ArchiveExtractor.Progress) -> Unit = {},
    ): PatchRollbackOutcome? = mutex.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            val current = PatchTransactionStore.load(context)
                .firstOrNull { it.transactionId == transactionId }
            when {
                current == null -> {
                    AppLog.i(
                        "PatchInstall",
                        "Patch tx=$transactionId was resolved before recovery reached it",
                    )
                    null
                }
                !current.isResumableRollback -> {
                    AppLog.i(
                        "PatchInstall",
                        "Patch tx=$transactionId is ${current.phase} (from ${current.orphanedFrom}) " +
                            "and is not an interrupted mutation; leaving it alone",
                    )
                    null
                }
                else -> rollbackLocked(context, current, onProgress)
            }
        }
    }

    /**
     * User-initiated retry for a record that is not (or no longer) a plain rollback point.
     *
     * The caller only supplies a transaction id: the record is re-read from the journal under the
     * transaction lock, so a snapshot from an open dialog can never decide what is restored. An
     * interrupted commit or rollback is resumed, an orphaned record is retried against the current
     * library, and a record whose backup was abandoned is refused without a single write. When the
     * retry still cannot proceed, the journal entry and the backup folder are both kept.
     */
    suspend fun retryRollback(
        context: Context,
        transactionId: String,
        onProgress: (ArchiveExtractor.Progress) -> Unit = {},
    ): PatchRollbackOutcome = mutex.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            val current = PatchTransactionStore.load(context)
                .firstOrNull { it.transactionId == transactionId }
                ?: return@withContext PatchRollbackOutcome.NoLongerAvailable(
                    "This patch",
                    "That patch record was already resolved, so there is nothing left to retry and " +
                        "AGM did not change any file.",
                )
            if (current.invalidated) {
                // The backup is gone or abandoned: retrying could only write nothing, so say so
                // instead of rewriting the record.
                val message = current.unresolvedReason
                    ?: "${current.label}'s patch backup was abandoned when the game was deleted or " +
                        "moved, so AGM cannot restore anything from this record."
                AppLog.w("PatchInstall", "Retry refused for tx=$transactionId: $message")
                return@withContext PatchRollbackOutcome.Unresolved(current.label, message)
            }
            AppLog.i(
                "PatchInstall",
                "Retrying rollback tx=$transactionId phase=${current.phase} " +
                    "(from ${current.orphanedFrom})",
            )
            rollbackLocked(context, current, onProgress)
        }
    }

    private suspend fun rollbackLocked(
        context: Context,
        snapshot: PatchTransactionRecord,
        onProgress: (ArchiveExtractor.Progress) -> Unit,
    ): PatchRollbackOutcome {
        // The caller's copy can be minutes old: it may have been discarded, forgotten, rolled back
        // or invalidated in the meantime. Only the persisted record may be acted on, and a record
        // that is gone is never written back.
        val record = PatchTransactionStore.load(context)
            .firstOrNull { it.transactionId == snapshot.transactionId }
            ?: run {
                val message = "${snapshot.label}'s patch record was already resolved, so there is " +
                    "nothing left to roll back and AGM did not change any file."
                AppLog.i(
                    "PatchInstall",
                    "Rollback tx=${snapshot.transactionId} skipped: the record is gone",
                )
                return PatchRollbackOutcome.NoLongerAvailable(snapshot.label, message)
            }

        // Nothing below this point may run until the record is proven to still describe the library.
        blockReason(record, loadLibrary(context))?.let { reason ->
            if (record.phase != PatchTransactionPhase.Orphaned || record.unresolvedReason != reason) {
                PatchTransactionStore.save(context, record.markOrphaned(reason))
            }
            AppLog.w("PatchInstall", "Refused rollback tx=${record.transactionId}: $reason")
            return PatchRollbackOutcome.Unresolved(record.label, reason)
        }

        val rolling = record.copy(
            phase = PatchTransactionPhase.RollingBack,
            orphanedFrom = null,
            unresolvedReason = null,
        )
        PatchTransactionStore.save(context, rolling)

        val destination = File(record.destinationRoot)
        val backupDir = File(record.backupDir)
        val problems = mutableListOf<String>()
        var restored = 0
        var removed = 0
        val total = record.added.size + record.replaced.size
        var index = 0

        for (file in record.replaced) {
            index++
            val target = File(destination, file.relativePath)
            val backup = File(backupDir, file.relativePath)
            val part = File(target.parentFile, target.name + PATCH_PART_SUFFIX)
            runCatching { if (part.exists()) part.delete() }
            if (!backup.isFile) {
                problems += "the backup of '${file.relativePath}' is missing"
                continue
            }
            val result = runCatching {
                target.parentFile?.mkdirs()
                copyFile(backup, part)
                if (part.length() != file.originalSize || sha256(part) != file.originalSha256) {
                    part.delete()
                    error("restored copy did not match the original")
                }
                if (target.exists() && !target.delete()) error("the patched file could not be removed")
                if (!part.renameTo(target)) {
                    part.delete()
                    error("the restored file could not be moved into place")
                }
            }
            if (result.isFailure) {
                problems += "'${file.relativePath}' could not be restored " +
                    "(${result.exceptionOrNull()?.message})"
            } else {
                restored++
            }
            onProgress(
                ArchiveExtractor.Progress(0L, 0L, index, total, "Restoring ${file.relativePath}"),
            )
        }

        for (file in record.added) {
            index++
            val target = File(destination, file.relativePath)
            val part = File(target.parentFile, target.name + PATCH_PART_SUFFIX)
            runCatching { if (part.exists()) part.delete() }
            if (target.exists() && !runCatching { target.delete() }.getOrDefault(false)) {
                problems += "'${file.relativePath}' added by the patch could not be removed"
            } else {
                removed++
            }
            onProgress(
                ArchiveExtractor.Progress(0L, 0L, index, total, "Removing ${file.relativePath}"),
            )
        }

        record.createdDirectories.sortedDescending().forEach { relative ->
            val directory = File(destination, relative)
            if (directory.isDirectory && directory.list()?.isEmpty() == true) {
                runCatching { directory.delete() }
            }
        }

        // Independent verification: never trust the loop above.
        for (file in record.replaced) {
            val target = File(destination, file.relativePath)
            if (!target.isFile || target.length() != file.originalSize ||
                runCatching { sha256(target) }.getOrNull() != file.originalSha256
            ) {
                problems += "'${file.relativePath}' does not match its pre-patch content"
            }
        }
        for (file in record.added) {
            if (File(destination, file.relativePath).exists()) {
                problems += "'${file.relativePath}' added by the patch is still present"
            }
        }

        if (problems.isNotEmpty()) {
            val message = "${record.label} could not be fully restored: ${problems.distinct().joinToString("; ")}. " +
                "AGM kept the backup at ${record.backupDir} and its recovery journal."
            PatchTransactionStore.save(context, rolling.copy(unresolvedReason = message))
            AppLog.e("PatchInstall", message)
            return PatchRollbackOutcome.Unresolved(record.label, message)
        }

        cleanupWorkDirectory(File(record.workDir))
        PatchTransactionStore.remove(context, record.transactionId)
        AppLog.i(
            "PatchInstall",
            "Rolled back tx=${record.transactionId} game=${record.managedGameId} " +
                "restored=$restored removed=$removed",
        )
        return PatchRollbackOutcome.RolledBack(
            label = record.label,
            storagePath = record.storagePath,
            restoredCount = restored,
            removedCount = removed,
        )
    }

    /** Discards a completed patch's rollback point once the user confirms the patch works. */
    suspend fun discardRollbackPoint(context: Context, record: PatchTransactionRecord): Boolean =
        mutex.withLock {
            withContext(NonCancellable + Dispatchers.IO) {
                require(record.phase == PatchTransactionPhase.Committed) {
                    "Only a committed patch transaction can be discarded."
                }
                val removed = cleanupWorkDirectory(File(record.workDir))
                PatchTransactionStore.remove(context, record.transactionId)
                removed
            }
        }

    /**
     * Drops a record whose backup can never be needed again, together with AGM's private work
     * folder. Game files are never touched.
     *
     * The caller's snapshot decides nothing: the record is re-read under the transaction lock and
     * checked with [patchForgetBlockReason], so an interrupted commit, an unfinished rollback, a
     * usable rollback point and any orphaned record are refused without deleting the work folder or
     * the journal entry. A refusal is an outcome, not an exception, and it mutates nothing.
     */
    suspend fun forgetRecord(context: Context, record: PatchTransactionRecord): PatchForgetOutcome =
        mutex.withLock {
            withContext(NonCancellable + Dispatchers.IO) {
                val current = PatchTransactionStore.load(context)
                    .firstOrNull { it.transactionId == record.transactionId }
                    ?: return@withContext PatchForgetOutcome.AlreadyGone(record.label)
                patchForgetBlockReason(current)?.let { reason ->
                    AppLog.w(
                        "PatchInstall",
                        "Refused to forget patch record tx=${current.transactionId} " +
                            "phase=${current.phase} (from ${current.orphanedFrom}): $reason",
                    )
                    return@withContext PatchForgetOutcome.Refused(current.label, reason)
                }
                val removed = cleanupWorkDirectory(File(current.workDir))
                PatchTransactionStore.remove(context, current.transactionId)
                AppLog.i("PatchInstall", "Forgot patch record tx=${current.transactionId} clean=$removed")
                PatchForgetOutcome.Forgotten(current.label, current.workDir, removed)
            }
        }

    /**
     * Re-checks every record against the current library and returns the reconciled journal.
     *
     * A committed record whose game was deleted or moved stops being a rollback point immediately,
     * so no list or count can offer it. A record that only looked stale (an unmounted folder, a
     * library read that failed once) returns to the *exact* phase it was orphaned from, unless its
     * backup was deliberately invalidated: only a record that was genuinely committed becomes a
     * rollback point again, while an interrupted commit or rollback goes back to being an
     * unresolved mutation that startup recovery finishes. A legacy record without recorded
     * provenance stays orphaned, because an unknown state must never be claimed as committed.
     */
    suspend fun reconcileWithLibrary(context: Context): List<PatchTransactionRecord> = mutex.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            val records = PatchTransactionStore.load(context)
            if (records.isEmpty()) return@withContext records
            val library = loadLibrary(context)
            var changed = false
            for (record in records) {
                if (record.invalidated) continue
                val reason = blockReason(record, library)
                val updated = when {
                    reason != null && record.phase == PatchTransactionPhase.Committed ->
                        record.markOrphaned(reason)
                    reason != null && record.phase == PatchTransactionPhase.Orphaned &&
                        record.unresolvedReason != reason ->
                        record.copy(unresolvedReason = reason)
                    reason == null && record.phase == PatchTransactionPhase.Orphaned ->
                        record.restoredPhase?.let {
                            record.copy(phase = it, orphanedFrom = null, unresolvedReason = null)
                        }
                    else -> null
                } ?: continue
                PatchTransactionStore.save(context, updated)
                changed = true
                AppLog.i(
                    "PatchInstall",
                    "Reconciled patch tx=${record.transactionId} ${record.phase} -> ${updated.phase}",
                )
            }
            if (changed) PatchTransactionStore.load(context) else records
        }
    }

    private fun copyFile(source: File, target: File) {
        target.parentFile?.mkdirs()
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(PATCH_COPY_BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.flush()
                output.fd.sync()
            }
        }
    }

    private fun cleanupWorkDirectory(workDir: File): Boolean {
        if (!workDir.exists()) return true
        val deleted = runCatching { workDir.deleteRecursively() }.getOrDefault(false)
        if (!deleted) AppLog.w("PatchInstall", "Could not delete patch work folder ${workDir.absolutePath}")
        return deleted
    }
}

/**
 * Finishes or reports every patch transaction that was interrupted. Committed transactions are left
 * alone: they are the user's rollback point, not an unresolved state. The initial load is only a
 * candidate list; each candidate is re-read under the transaction lock before anything is undone,
 * so a transaction that commits while recovery waits is never rolled back from a stale snapshot.
 */
suspend fun recoverPendingPatchInstalls(context: Context): List<PatchRecoveryOutcome> {
    val appContext = context.applicationContext
    val records = PatchInstallTransaction.reconcileWithLibrary(appContext)
    val unresolved = records.filter { it.isUnresolved }
    if (unresolved.isEmpty()) return emptyList()
    return unresolved.mapNotNull { record ->
        if (record.invalidated) {
            // The backup was deliberately abandoned; there is nothing to finish, only to report.
            return@mapNotNull PatchRecoveryOutcome.Unresolved(
                record.label,
                record.unresolvedReason
                    ?: "${record.label} has an invalidated patch record that AGM could not clean up.",
            )
        }
        if (!record.isResumableRollback) {
            // Orphaned from a committed patch, or from a phase this AGM version never recorded.
            // Either way the install itself is not known to be half-finished, so recovery reports
            // it and never rewrites a single game file behind the user's back.
            return@mapNotNull PatchRecoveryOutcome.Unresolved(
                record.label,
                record.unresolvedReason
                    ?: "${record.label} has a patch record AGM cannot match to its library and " +
                        "whose original state is unknown, so nothing was changed. Its backup is " +
                        "kept; retry the rollback from the installed-patches list once the game is " +
                        "back where the record expects it.",
            )
        }
        AppLog.w(
            "PatchInstall",
            "Recovering interrupted patch tx=${record.transactionId} phase=${record.phase} " +
                "game=${record.managedGameId}",
        )
        when (
            val outcome = PatchInstallTransaction.rollbackIfStillUnresolved(
                appContext,
                record.transactionId,
            )
        ) {
            null -> null
            is PatchRollbackOutcome.RolledBack -> PatchRecoveryOutcome.RolledBack(
                record.label,
                "AGM was interrupted while patching ${record.label}, so the patch was undone and " +
                    "${outcome.restoredCount} file(s) were restored.",
            )
            is PatchRollbackOutcome.Unresolved -> PatchRecoveryOutcome.Unresolved(
                record.label,
                outcome.message,
            )
            is PatchRollbackOutcome.NoLongerAvailable -> null
        }
    }
}
