package com.example.f95updater

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Durable, non-expiring journal for managed-game patch installs.
 *
 * A record exists from the moment before the first destination byte is mutated until either the
 * install is rolled back or the user discards the rollback point. Recovery reads it on every app
 * start, so it must never be cleared on a guess.
 */

@Serializable
enum class PatchTransactionPhase {
    /** Backups are complete and destination files are being replaced/added. */
    Committing,

    /** Every file was written and verified. The record is now the rollback point. */
    Committed,

    /** A rollback is in progress; originals are being restored from the backup folder. */
    RollingBack,

    /**
     * The record no longer matches the library it was written for: the managed game was deleted,
     * moved, or its patched folder is gone. Nothing on disk is touched for such a record; it exists
     * only so the situation can be reported and, once cleared, cleaned up.
     */
    Orphaned,
}

@Serializable
data class PatchReplacedFile(
    val relativePath: String,
    val originalSha256: String,
    val originalSize: Long,
)

@Serializable
data class PatchAddedFile(
    val relativePath: String,
    val sha256: String,
    val size: Long,
)

@Serializable
data class PatchTransactionRecord(
    val schemaVersion: Int = 1,
    val transactionId: String,
    val phase: PatchTransactionPhase,
    val managedGameId: String,
    val label: String,
    val storagePath: String,
    val destinationRoot: String,
    val workDir: String,
    val backupDir: String,
    val archiveName: String,
    val added: List<PatchAddedFile>,
    val replaced: List<PatchReplacedFile>,
    val createdDirectories: List<String>,
    val unresolvedReason: String? = null,
    /**
     * True when the user explicitly acknowledged that AGM could not prove this patch fits the
     * installed build and asked for it to be applied anyway. Purely informational for the installed
     * patches and rollback UI: an overridden install is journalled, backed up and rolled back
     * exactly like any other.
     */
    val compatibilityOverridden: Boolean = false,
    /** The compatibility reason the user was shown when they authorised the override. */
    val compatibilityOverrideReason: String? = null,
    /**
     * The phase this record was actually in when it was marked [PatchTransactionPhase.Orphaned].
     *
     * Orphaning is not a state of the install, it is a statement about the *library*: the game was
     * deleted, moved, or its storage was temporarily unreadable. The underlying install can be
     * mid-commit, mid-rollback, or long finished, and those cases must be resolved in completely
     * different ways once the library matches again. Null means the record predates this field (or
     * is not orphaned at all); such a record is never promoted back to
     * [PatchTransactionPhase.Committed] because AGM cannot prove the destination was ever fully
     * written.
     */
    val orphanedFrom: PatchTransactionPhase? = null,
    /**
     * True once the backup this record points at has been deliberately abandoned or destroyed
     * because the managed game was deleted or moved away from [storagePath]. Such a record can
     * never be applied again: it is kept only when the cleanup could not be verified, so the
     * leftover folder can be reported instead of silently claimed as removed.
     */
    val invalidated: Boolean = false,
    val savedAt: Long = System.currentTimeMillis(),
) {
    val isUnresolved: Boolean get() = phase != PatchTransactionPhase.Committed

    /** Only a committed, still-valid record may be offered to the user as a rollback point. */
    val isUsableRollbackPoint: Boolean
        get() = phase == PatchTransactionPhase.Committed && !invalidated

    /** An invalidated record belongs to a location that no longer exists; it blocks nothing. */
    val blocksNewPatch: Boolean get() = !invalidated

    /**
     * The phase an orphaned record must return to once the library matches it again, or null when
     * there is nothing to restore (the record is not orphaned, or it predates [orphanedFrom] and
     * its real phase is therefore unknown).
     */
    val restoredPhase: PatchTransactionPhase?
        get() = if (phase != PatchTransactionPhase.Orphaned) {
            null
        } else {
            orphanedFrom?.takeIf { it != PatchTransactionPhase.Orphaned }
        }

    /**
     * True when startup recovery may finish this record by rolling it back. Only an interrupted
     * mutation qualifies: a committed patch is the user's rollback point and a record whose real
     * phase is unknown is reported, never undone behind the user's back.
     */
    val isResumableRollback: Boolean
        get() = !invalidated && when (phase) {
            PatchTransactionPhase.Committing, PatchTransactionPhase.RollingBack -> true
            PatchTransactionPhase.Committed -> false
            PatchTransactionPhase.Orphaned -> restoredPhase == PatchTransactionPhase.Committing ||
                restoredPhase == PatchTransactionPhase.RollingBack
        }

    /**
     * True only when this record's backup can never be needed to restore a game file again, so
     * deleting the record and its private work folder is plain cleanup instead of data loss.
     *
     * The only such record is an [invalidated] one: its backup was deliberately abandoned when the
     * game was deleted or moved away, a rollback must never touch a game with it, and the record
     * survives only so the leftover folder can be reported. Everything else - a committed rollback
     * point, an interrupted commit or rollback, an orphaned record of any provenance, and a legacy
     * record whose real phase is unknown - keeps its backup, because that backup is either the
     * user's only way back or the only copy of the pre-patch files.
     */
    val isSafeToForget: Boolean
        get() = when {
            isResumableRollback -> false
            isUsableRollbackPoint -> false
            else -> invalidated
        }
}

/**
 * Why [record] must not be forgotten, or null when forgetting it is safe cleanup.
 *
 * Pure and read-only, so the same rule can be enforced inside the transaction and used to decide
 * which buttons the installed-patches list may show.
 */
internal fun patchForgetBlockReason(record: PatchTransactionRecord): String? {
    if (record.isSafeToForget) return null
    if (record.isResumableRollback) {
        val phase = record.orphanedFrom ?: record.phase
        val what = if (phase == PatchTransactionPhase.RollingBack) {
            "an unfinished rollback"
        } else {
            "an interrupted patch install"
        }
        return "${record.label} has $what from '${record.archiveName}'. The backup in " +
            "${record.backupDir} is the only copy of the files the patch replaced, so AGM will not " +
            "delete it. Retry the rollback instead; the record can only be removed once the game " +
            "files no longer depend on it."
    }
    if (record.isUsableRollbackPoint) {
        return "${record.label} still has a usable rollback point from '${record.archiveName}'. " +
            "Roll it back, or keep the patch and delete the backup explicitly; it is never removed " +
            "as if it were stale."
    }
    val origin = record.orphanedFrom
    val provenance = if (origin == null) {
        "AGM cannot prove which state its patch install was left in"
    } else {
        "its patch install was left ${origin.name}"
    }
    return "${record.label}'s patch record does not currently match the AGM library and " +
        "$provenance, so the backup in ${record.backupDir} may still be the only copy of the " +
        "original files. AGM keeps it. Retry the rollback once the game is back where the record " +
        "expects it."
}

/** The buttons the installed-patches list may offer for a record. Pure, so it is unit-testable. */
enum class PatchRecordAction { RollBack, DiscardBackup, RetryRollback, RemoveRecord }

/**
 * Classifies [record] into the actions that are safe to offer.
 *
 * Remove is offered only for a record that [PatchTransactionRecord.isSafeToForget]; every
 * interrupted or merely stale record is offered a retry instead, which re-reads the journal under
 * the transaction lock and either finishes the rollback or reports why it still cannot.
 */
fun patchRecordActions(record: PatchTransactionRecord): List<PatchRecordAction> = when {
    record.isUsableRollbackPoint ->
        listOf(PatchRecordAction.RollBack, PatchRecordAction.DiscardBackup)
    record.isSafeToForget -> listOf(PatchRecordAction.RemoveRecord)
    else -> listOf(PatchRecordAction.RetryRollback)
}

/** The precise reason a record is not a usable rollback point, for the list dialog. */
fun patchRecordUnresolvedSummary(record: PatchTransactionRecord): String =
    record.unresolvedReason ?: patchForgetBlockReason(record)
        ?: "${record.label}'s patch backup was abandoned, so this record can only be removed."

/**
 * Marks a record [PatchTransactionPhase.Orphaned] while remembering the phase it was actually in.
 *
 * Called from every place that orphans a record, so reconciliation can later put the record back
 * into that exact phase instead of guessing that it was committed. An already orphaned record keeps
 * the provenance it was first orphaned with (including "unknown" for a legacy record).
 */
internal fun PatchTransactionRecord.markOrphaned(reason: String?): PatchTransactionRecord = copy(
    phase = PatchTransactionPhase.Orphaned,
    orphanedFrom = orphanedFrom ?: phase.takeIf { it != PatchTransactionPhase.Orphaned },
    unresolvedReason = reason,
)

@Serializable
private data class PatchTransactionEnvelope(
    val schemaVersion: Int = 1,
    val records: List<PatchTransactionRecord> = emptyList(),
)

object PatchTransactionStore {
    private const val PREFS = "agm_patch_transactions"
    private const val KEY = "records_json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    internal fun encode(records: List<PatchTransactionRecord>): String =
        json.encodeToString(PatchTransactionEnvelope.serializer(), PatchTransactionEnvelope(records = records))

    internal fun decode(raw: String): List<PatchTransactionRecord> =
        json.decodeFromString(PatchTransactionEnvelope.serializer(), raw).records

    fun load(context: Context): List<PatchTransactionRecord> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { decode(raw) }.getOrElse {
            // The journal is the only thing that can undo a mutation. Never silently drop it.
            AppLog.e("PatchInstall", "Patch journal is unreadable and was kept as-is", it)
            throw IllegalStateException(
                "AGM's patch recovery journal is unreadable, so patch installs are disabled until " +
                    "it is repaired.",
                it,
            )
        }
    }

    fun find(context: Context, managedGameId: String): PatchTransactionRecord? =
        load(context).firstOrNull { it.managedGameId == managedGameId && it.blocksNewPatch }

    /**
     * Single-write upsert keyed by [PatchTransactionRecord.transactionId]. A phase change must never
     * be two writes, otherwise a crash between them would erase the only record that can undo a
     * mutation.
     */
    fun save(context: Context, record: PatchTransactionRecord) {
        val others = load(context).filterNot { it.transactionId == record.transactionId }
        val conflicting = others.firstOrNull {
            it.managedGameId == record.managedGameId && it.blocksNewPatch && record.blocksNewPatch
        }
        check(conflicting == null) {
            "${record.label} already has patch transaction ${conflicting?.transactionId}."
        }
        write(context, others + record.copy(savedAt = System.currentTimeMillis()))
    }

    fun remove(context: Context, transactionId: String) {
        write(context, load(context).filterNot { it.transactionId == transactionId })
    }

    private fun write(context: Context, records: List<PatchTransactionRecord>) {
        val committed = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, encode(records))
            .commit()
        check(committed) { "Could not persist the patch recovery journal." }
        AppLog.i("PatchInstall", "Journal now holds ${records.size} patch transaction(s)")
    }
}

/** Raised when a managed-game lifecycle change would strand a patch backup. */
class ManagedGamePatchTransactionException(message: String) : IllegalStateException(message)

/**
 * Reason why [managedGameId] must not be deleted or relocated right now, or null. An unresolved
 * transaction means the private backup folder is the only copy of the original files, so the game
 * must not move or disappear underneath it.
 */
internal fun unresolvedPatchBlocker(
    records: List<PatchTransactionRecord>,
    managedGameId: String,
): String? {
    val pending = records.firstOrNull {
        it.managedGameId == managedGameId && it.blocksNewPatch && it.isUnresolved
    } ?: return null
    return "${pending.label} has an unresolved patch transaction (${pending.phase}) from " +
        "'${pending.archiveName}'. Its backup folder is currently the only copy of the original " +
        "files, so AGM will not delete or move the game until that transaction is resolved."
}

/** The committed rollback points that belong to [canonicalPath] and can still be applied. */
internal fun patchRecordsAtLocation(
    records: List<PatchTransactionRecord>,
    managedGameId: String,
    canonicalPath: String,
): List<PatchTransactionRecord> = records.filter {
    it.managedGameId == managedGameId &&
        it.blocksNewPatch &&
        runCatching { canonicalManagedGamePath(it.storagePath) }.getOrNull() == canonicalPath
}

/**
 * Lets [ManagedGameStore] keep the patch journal truthful without knowing how the journal works.
 * The whole lifecycle change runs inside [withLifecycleGuard] so a rollback can never interleave
 * with a delete or a relocation of the same game.
 */
interface ManagedGamePatchJournal {
    suspend fun <T> withLifecycleGuard(
        managedGameId: String,
        block: suspend (ManagedGamePatchCleanup) -> T,
    ): T

    /** Used by tests and by callers that must not touch the journal at all. */
    object None : ManagedGamePatchJournal {
        override suspend fun <T> withLifecycleGuard(
            managedGameId: String,
            block: suspend (ManagedGamePatchCleanup) -> T,
        ): T = block(ManagedGamePatchCleanup { _, _, _ -> })
    }
}

fun interface ManagedGamePatchCleanup {
    /**
     * Invalidates every rollback point recorded for [canonicalPath] and deletes its private backup.
     * Called only after the lifecycle change itself succeeded.
     */
    fun invalidateRollbackPoints(managedGameId: String, canonicalPath: String, cause: String)
}

class PatchTransactionJournal(context: Context) : ManagedGamePatchJournal {
    private val appContext = context.applicationContext

    override suspend fun <T> withLifecycleGuard(
        managedGameId: String,
        block: suspend (ManagedGamePatchCleanup) -> T,
    ): T = PatchInstallTransaction.withJournalLock {
        // An unreadable journal cannot be proven empty, so it refuses the lifecycle change too.
        unresolvedPatchBlocker(PatchTransactionStore.load(appContext), managedGameId)?.let {
            throw ManagedGamePatchTransactionException(it)
        }
        block(
            ManagedGamePatchCleanup { gameId, canonicalPath, cause ->
                invalidatePatchRollbackPoints(appContext, gameId, canonicalPath, cause)
            },
        )
    }
}

/**
 * Discards the rollback points that belonged to a location the managed game has just left.
 *
 * The record is written as invalidated *before* a single backup byte is destroyed, so a crash in
 * the middle can never leave a record that still claims to be a usable rollback point over a backup
 * that is already gone. Only a verified cleanup removes the record; otherwise the invalidated
 * record is kept so the leftover folder is reported instead of silently claimed as removed.
 */
internal fun invalidatePatchRollbackPoints(
    context: Context,
    managedGameId: String,
    canonicalPath: String,
    cause: String,
) {
    val targets = patchRecordsAtLocation(PatchTransactionStore.load(context), managedGameId, canonicalPath)
    for (target in targets) {
        val marked = target.markOrphaned(
            "$cause AGM is deleting the patch backup in ${target.workDir}.",
        ).copy(invalidated = true)
        PatchTransactionStore.save(context, marked)
        val workDir = File(target.workDir)
        val cleaned = runCatching {
            if (!workDir.exists()) true else workDir.deleteRecursively() && !workDir.exists()
        }.getOrDefault(false)
        if (cleaned) {
            PatchTransactionStore.remove(context, target.transactionId)
            AppLog.i(
                "PatchInstall",
                "Discarded rollback point tx=${target.transactionId} after $cause",
            )
        } else {
            PatchTransactionStore.save(
                context,
                marked.copy(
                    unresolvedReason = "$cause The patch backup in ${target.workDir} could not be " +
                        "deleted, so it is no longer a rollback point and AGM will never restore " +
                        "from it. Delete that folder yourself.",
                ),
            )
            AppLog.w(
                "PatchInstall",
                "Kept invalidated rollback point tx=${target.transactionId}; " +
                    "${target.workDir} could not be deleted",
            )
        }
    }
}
