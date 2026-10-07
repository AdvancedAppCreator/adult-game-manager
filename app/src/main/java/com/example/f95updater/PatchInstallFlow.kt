package com.example.f95updater

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A refusal is a first-class result: it always names the archive and why AGM stopped. */
data class PatchRefusalReport(
    val archiveName: String,
    val reason: String,
    val details: List<String>,
)

/**
 * Drives the whole patch-install feature: SAF pick -> cache copy -> header validation -> staging ->
 * identity proof -> preview -> transactional install, plus explicit rollback of a completed patch.
 *
 * The flow never mutates an installed game until [confirmInstall] is called, and it never deletes
 * the user's archive.
 */
class PatchInstallFlow(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var archiveName by mutableStateOf<String?>(null); private set
    var phase by mutableStateOf(JoiPlayExtractFlow.Phase.Preparing); private set
    var progress by mutableStateOf<ArchiveExtractor.Progress?>(null); private set
    var autoTryStatus by mutableStateOf<String?>(null); private set
    var passwordPromptFor by mutableStateOf<ArchiveExtractor.Format?>(null); private set
    var savedPasswordsFailed by mutableStateOf(false); private set
    var lastPasswordWrong by mutableStateOf(false); private set
    var cancellationAllowed by mutableStateOf(true); private set
    var preview by mutableStateOf<PatchInstallPreview?>(null); private set

    /**
     * Whether the user currently acknowledges the compatibility override offered by [preview].
     *
     * It is deliberately *not* a UI-owned flag: it is dropped whenever the preview it belongs to is
     * published, replaced or cleared, so a dialog rebuilt after a configuration change or a process
     * recreation always starts unchecked and can never carry consent over to a different target.
     */
    var overrideAcknowledged by mutableStateOf(false); private set
    var refusal by mutableStateOf<PatchRefusalReport?>(null); private set
    var errorMessage by mutableStateOf<String?>(null); private set
    var result by mutableStateOf<PatchInstallResult?>(null); private set
    var rollbackOutcome by mutableStateOf<PatchRollbackOutcome?>(null); private set
    var rollbackPoints by mutableStateOf<List<PatchTransactionRecord>>(emptyList()); private set

    /** Only these may be offered as a rollback point or counted as an installed patch. */
    val usableRollbackPoints: List<PatchTransactionRecord>
        get() = rollbackPoints.filter { it.isUsableRollbackPoint }

    private var currentJob: Job? = null
    private var pending: Pending? = null
    /**
     * Bumped by every accepted start/commit/rollback. A job that lost the pipeline must not discard
     * the staging folder or clear in-flight state that now belongs to its successor.
     */
    private var generation = 0L

    val inProgress: Boolean
        get() = progress != null || (phase == JoiPlayExtractFlow.Phase.Cancelling && archiveName != null)

    val busy: Boolean
        get() = inProgress ||
            passwordPromptFor != null ||
            preview != null ||
            refusal != null ||
            errorMessage != null ||
            result != null ||
            rollbackOutcome != null

    val activeOperation: InstallOperationOwner?
        get() = if (!busy) {
            null
        } else {
            InstallOperationOwner(
                kind = InstallOperationKind.Patch,
                detail = archiveName,
                cancellable = cancellationAllowed,
                awaitingUser = !inProgress,
            )
        }

    /** True while [gen] is still the generation that owns this flow's staging folder and state. */
    private fun owns(gen: Long): Boolean {
        if (gen == generation) return true
        AppLog.w("PatchInstall", "Stale patch job gen=$gen ignored (current=$generation)")
        return false
    }

    fun refreshRollbackPoints() {
        scope.launch {
            rollbackPoints = withContext(Dispatchers.IO) {
                // Reconciles first: a record whose game was deleted or moved is never a rollback point.
                runCatching { PatchInstallTransaction.reconcileWithLibrary(context) }
                    .onFailure { AppLog.e("PatchInstall", "Could not read the patch journal", it) }
                    .getOrDefault(emptyList())
            }
        }
    }

    /**
     * Begins the analysis of a picked patch archive, or refuses when this flow is already busy.
     *
     * It used to call [cancelInProgress] first, which does nothing at all once
     * `cancellationAllowed` is false — i.e. exactly during the non-interruptible commit. The second
     * start then overwrote `pending`, and the commit's own `discardPending()` deleted the new
     * archive's cache copy and staging folder out from under it.
     */
    fun start(archiveUri: Uri): InstallStartOutcome {
        InstallOperationGuard.refuse(activeOperation, InstallOperationKind.Patch)?.let { reason ->
            AppLog.w("PatchInstall", "Refused a second patch install: $reason")
            return InstallStartOutcome.Refused(reason)
        }
        val gen = ++generation
        cancellationAllowed = true
        phase = JoiPlayExtractFlow.Phase.Preparing
        archiveName = ArchiveUriSource.displayName(context, archiveUri).ifBlank { "archive" }
        progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Reading ${archiveName}…")
        currentJob = scope.launch {
            try {
                prepare(archiveUri)
            } catch (ce: CancellationException) {
                AppLog.i("PatchInstall", "Patch analysis cancelled")
                if (owns(gen)) {
                    discardPending()
                    clearInFlightState()
                }
                throw ce
            } catch (error: Throwable) {
                AppLog.e("PatchInstall", "Patch analysis failed", error)
                if (owns(gen)) {
                    fail("AGM could not read the patch archive: ${error.message ?: error::class.simpleName}")
                }
            }
        }
        return InstallStartOutcome.Started
    }

    fun submitPassword(password: String) {
        val work = pending ?: return
        val gen = generation
        passwordPromptFor = null
        savedPasswordsFailed = false
        lastPasswordWrong = false
        phase = JoiPlayExtractFlow.Phase.Preparing
        progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Opening ${work.archiveName}…")
        currentJob = scope.launch {
            try {
                when (resolveWithPassword(work, password.toCharArray())) {
                    PasswordAttempt.Ok -> PasswordVault.remember(password)
                    PasswordAttempt.Wrong -> {
                        lastPasswordWrong = true
                        passwordPromptFor = work.format
                        progress = null
                        currentJob = null
                    }
                    PasswordAttempt.Stopped -> Unit
                }
            } catch (ce: CancellationException) {
                if (owns(gen)) {
                    discardPending()
                    clearInFlightState()
                }
                throw ce
            }
        }
    }

    fun cancelPasswordPrompt() {
        discardPending()
        clearInFlightState()
    }

    fun cancelInProgress() {
        if (!cancellationAllowed) return
        val job = currentJob
        if (job?.isActive == true) {
            phase = JoiPlayExtractFlow.Phase.Cancelling
            progress = progress ?: ArchiveExtractor.Progress(0L, 0L, 0, 1, "Cancelling…")
            passwordPromptFor = null
            autoTryStatus = null
            job.cancel()
            return
        }
        discardPending()
        clearInFlightState()
    }

    fun cancelPreview() {
        preview = null
        overrideAcknowledged = false
        discardPending()
        clearInFlightState()
    }

    /**
     * Records the user's acknowledgement of the compatibility override for the *currently shown*
     * preview. It can never be set while no preview is open, and it is cleared again the moment the
     * preview is replaced or cancelled.
     */
    fun acknowledgeOverride(acknowledged: Boolean) {
        val open = preview
        if (open == null || !open.requiresOverrideAcknowledgement) {
            overrideAcknowledged = false
            return
        }
        overrideAcknowledged = acknowledged
    }

    fun acknowledgeRefusal() {
        refusal = null
    }

    fun acknowledgeError() {
        errorMessage = null
    }

    fun acknowledgeResult() {
        result = null
        clearInFlightState()
        refreshRollbackPoints()
    }

    fun acknowledgeRollback() {
        rollbackOutcome = null
        refreshRollbackPoints()
    }

    /**
     * Commits the staged patch. This is the non-interruptible step, so it refuses outright rather
     * than trying to cancel anything: from here on `cancellationAllowed` is false and every other
     * entry point must wait.
     *
     * When the open preview is a compatibility override, the install starts only while the
     * acknowledgement for that exact preview is currently ticked, and only into the target the
     * analysis proved — the preview cannot redirect the install to another game.
     */
    fun confirmInstall(): InstallStartOutcome {
        if (currentJob?.isActive == true) {
            val reason = InstallOperationGuard.refuse(activeOperation, InstallOperationKind.Patch)
                ?: "AGM is already working on this patch."
            AppLog.w("PatchInstall", "Refused a second patch commit: $reason")
            return InstallStartOutcome.Refused(reason)
        }
        val work = pending ?: return InstallStartOutcome.Refused("There is no staged patch to install.")
        val game = work.game ?: return InstallStartOutcome.Refused("There is no staged patch to install.")
        val plan = work.plan ?: return InstallStartOutcome.Refused("There is no staged patch to install.")
        val staging = work.stagingRoot ?: return InstallStartOutcome.Refused("There is no staged patch to install.")
        val shown = preview ?: return InstallStartOutcome.Refused("There is no staged patch to install.")
        if (shown.targetManagedGameId != game.id) {
            // The preview and the staged target must be the same game or nothing is installed.
            AppLog.w(
                "PatchInstall",
                "Refused a patch commit: preview target ${shown.targetManagedGameId} is not the " +
                    "staged target ${game.id}",
            )
            return InstallStartOutcome.Refused(
                "AGM's patch target changed while the confirmation was open, so nothing was installed.",
            )
        }
        PatchOverrideGate.blockReason(shown, overrideAcknowledged)?.let { reason ->
            AppLog.w("PatchInstall", "Refused an unacknowledged patch override: $reason")
            return InstallStartOutcome.Refused(reason)
        }
        val overrideReason = PatchOverrideGate.overrideReason(shown, overrideAcknowledged)
        if (overrideReason != null) AppLog.w("PatchInstall", PatchOverrideGate.logLine(shown))
        val gen = ++generation
        preview = null
        overrideAcknowledged = false
        cancellationAllowed = false
        phase = JoiPlayExtractFlow.Phase.Preparing
        progress = ArchiveExtractor.Progress(0L, plan.totalBytes, 0, plan.fileCount, "Verifying staged files…")
        currentJob = scope.launch {
            val installed = runCatching {
                PatchInstallTransaction.install(
                    context = context,
                    game = game,
                    archiveName = work.archiveName,
                    stagingRoot = staging,
                    plan = plan,
                    compatibilityOverrideReason = overrideReason,
                ) { update -> progress = update }
            }
            if (!owns(gen)) return@launch
            discardPending()
            clearInFlightState()
            installed
                .onSuccess {
                    result = it
                    AppLog.i(
                        "PatchInstall",
                        "Installed ${it.archiveName} into ${it.label}: " +
                            "added=${it.addedCount} replaced=${it.replacedCount} " +
                            "compatibilityOverridden=${it.compatibilityOverridden}",
                    )
                }
                .onFailure { error ->
                    AppLog.e("PatchInstall", "Patch install failed", error)
                    errorMessage = error.message ?: "The patch could not be installed."
                }
            refreshRollbackPoints()
        }
        return InstallStartOutcome.Started
    }

    fun rollback(record: PatchTransactionRecord): InstallStartOutcome {
        if (!record.isUsableRollbackPoint) {
            // A stale record is reported, never used to walk over the current installation.
            rollbackOutcome = PatchRollbackOutcome.Unresolved(
                record.label,
                record.unresolvedReason
                    ?: "${record.label}'s patch record no longer matches the AGM library, so AGM did " +
                        "not restore anything.",
            )
            refreshRollbackPoints()
            return InstallStartOutcome.Started
        }
        refuseWhileRunning("rollback")?.let { return it }
        val gen = ++generation
        cancellationAllowed = false
        archiveName = record.archiveName
        phase = JoiPlayExtractFlow.Phase.Preparing
        progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Restoring ${record.label}…")
        currentJob = scope.launch {
            val outcome = runCatching {
                PatchInstallTransaction.rollback(context, record) { update -> progress = update }
            }
            if (!owns(gen)) return@launch
            clearInFlightState()
            outcome
                .onSuccess {
                    if (it is PatchRollbackOutcome.NoLongerAvailable) {
                        // The record was resolved elsewhere while the dialog was open. Report it as
                        // information: nothing was touched, so it must not become a blocking error.
                        AppLog.i("PatchInstall", "Rollback skipped: ${it.message}")
                    }
                    rollbackOutcome = it
                }
                .onFailure { error ->
                    AppLog.e("PatchInstall", "Patch rollback failed", error)
                    errorMessage = "The rollback failed: ${error.message}"
                }
            refreshRollbackPoints()
        }
        return InstallStartOutcome.Started
    }

    fun discardRollbackPoint(record: PatchTransactionRecord) {
        scope.launch {
            runCatching { PatchInstallTransaction.discardRollbackPoint(context, record) }
                .onFailure { AppLog.w("PatchInstall", "Could not discard patch backup", it) }
            refreshRollbackPoints()
        }
    }

    /** Retries the rollback/recovery of a record that is not a plain, usable rollback point. */
    fun retryRollback(record: PatchTransactionRecord): InstallStartOutcome {
        refuseWhileRunning("rollback retry")?.let { return it }
        val gen = ++generation
        cancellationAllowed = false
        archiveName = record.archiveName
        phase = JoiPlayExtractFlow.Phase.Preparing
        progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Restoring ${record.label}…")
        currentJob = scope.launch {
            val outcome = runCatching {
                // Only the transaction id travels: the record is re-read under the transaction lock.
                PatchInstallTransaction.retryRollback(context, record.transactionId) { update ->
                    progress = update
                }
            }
            if (!owns(gen)) return@launch
            clearInFlightState()
            outcome
                .onSuccess {
                    if (it is PatchRollbackOutcome.Unresolved) {
                        AppLog.w("PatchInstall", "Retry left tx=${record.transactionId} unresolved: ${it.message}")
                    }
                    rollbackOutcome = it
                }
                .onFailure { error ->
                    AppLog.e("PatchInstall", "Patch rollback retry failed", error)
                    errorMessage = "The rollback retry failed: ${error.message}"
                }
            refreshRollbackPoints()
        }
        return InstallStartOutcome.Started
    }

    /**
     * Rollbacks touch the very same game files a running analysis or commit is staging, so they
     * refuse while a job is in flight instead of trying to cancel one that may be uncancellable.
     */
    private fun refuseWhileRunning(what: String): InstallStartOutcome.Refused? {
        if (currentJob?.isActive != true && !inProgress) return null
        val reason = InstallOperationGuard.refuse(activeOperation, InstallOperationKind.Patch)
            ?: "AGM is already working on a patch."
        AppLog.w("PatchInstall", "Refused a patch $what: $reason")
        return InstallStartOutcome.Refused(reason)
    }

    /**
     * Removes a record whose backup can never be needed again. Game files are never touched, and a
     * record that still protects a game is refused by the transaction and reported, not deleted.
     */
    fun forgetRecord(record: PatchTransactionRecord) {
        scope.launch {
            runCatching { PatchInstallTransaction.forgetRecord(context, record) }
                .onSuccess { outcome ->
                    when (outcome) {
                        is PatchForgetOutcome.Refused -> errorMessage = outcome.reason
                        is PatchForgetOutcome.Forgotten -> if (!outcome.workDirDeleted) {
                            errorMessage = "AGM removed the stale patch record for ${outcome.label} " +
                                "but could not delete ${outcome.workDir}. Delete that folder yourself."
                        }
                        is PatchForgetOutcome.AlreadyGone -> Unit
                    }
                }
                .onFailure { error ->
                    AppLog.w("PatchInstall", "Could not forget patch record", error)
                    errorMessage = "AGM could not remove the stale patch record for ${record.label}: " +
                        "${error.message}"
                }
            refreshRollbackPoints()
        }
    }

    // ---------------------------------------------------------------- pipeline

    private enum class PasswordAttempt { Ok, Wrong, Stopped }

    private suspend fun prepare(archiveUri: Uri) {
        val name = archiveName ?: "archive"
        val totalBytes = ArchiveUriSource.sizeBytes(context, archiveUri)
        val cached = ArchiveUriSource.copyToCache(context, archiveUri, name, "patch_in_") { written ->
            progress = ArchiveExtractor.Progress(
                written,
                if (totalBytes > 0) totalBytes else written,
                0,
                1,
                "Reading $name…",
            )
        }
        val format = ArchiveExtractor.detectFormatByExt(cached.name)
            ?: withContext(Dispatchers.IO) { cached.inputStream().use { ArchiveExtractor.detectFormat(it) } }
        if (format == null) {
            cached.delete()
            refuse(name, "Unknown archive format. AGM installs patches from ZIP, RAR and 7Z archives.")
            return
        }
        pending = Pending(archiveName = name, archive = cached, format = format)
        when (resolveWithPassword(pending!!, password = null)) {
            PasswordAttempt.Ok, PasswordAttempt.Stopped -> Unit
            PasswordAttempt.Wrong -> onNeedsPasswordInitial(format)
        }
    }

    private fun onNeedsPasswordInitial(format: ArchiveExtractor.Format) {
        lastPasswordWrong = false
        savedPasswordsFailed = false
        progress = null
        currentJob = null
        if (PasswordVault.isEmpty()) passwordPromptFor = format else autoTryAll(format)
    }

    private fun autoTryAll(format: ArchiveExtractor.Format) {
        val work = pending ?: return
        val gen = generation
        val candidates = PasswordVault.all()
        phase = JoiPlayExtractFlow.Phase.Preparing
        progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Opening ${work.archiveName}…")
        currentJob = scope.launch {
            try {
                for ((index, candidate) in candidates.withIndex()) {
                    if (!isActive) return@launch
                    autoTryStatus = "Trying saved password ${index + 1}/${candidates.size}…"
                    when (resolveWithPassword(work, candidate.toCharArray())) {
                        PasswordAttempt.Ok -> {
                            PasswordVault.remember(candidate)
                            autoTryStatus = null
                            return@launch
                        }
                        PasswordAttempt.Wrong -> Unit
                        PasswordAttempt.Stopped -> {
                            autoTryStatus = null
                            return@launch
                        }
                    }
                }
                autoTryStatus = null
                savedPasswordsFailed = true
                passwordPromptFor = format
                progress = null
                currentJob = null
            } catch (ce: CancellationException) {
                if (owns(gen)) {
                    discardPending()
                    clearInFlightState()
                }
                throw ce
            }
        }
    }

    /**
     * Lists, validates, stages and analyses the archive with [password]. Returns [PasswordAttempt.Wrong]
     * only when the archive itself reported a password problem; every other stop is surfaced to the
     * user as a refusal or an error.
     */
    private suspend fun resolveWithPassword(work: Pending, password: CharArray?): PasswordAttempt {
        val listing = PatchArchiveReader.list(work.archive, work.format, password)
        when (listing) {
            is PatchArchiveReader.Listing.NeedsPassword -> return PasswordAttempt.Wrong
            is PatchArchiveReader.Listing.Failed -> {
                refuse(
                    work.archiveName,
                    listing.message,
                    listOf(
                        "AGM refuses to install an archive whose entries it cannot inspect first.",
                    ),
                )
                return PasswordAttempt.Stopped
            }
            is PatchArchiveReader.Listing.Ok -> Unit
        }
        val scan = PatchArchiveScanner.scan((listing as PatchArchiveReader.Listing.Ok).entries)
        if (scan is PatchArchiveScan.Rejected) {
            refuse(work.archiveName, scan.reason)
            return PasswordAttempt.Stopped
        }
        val accepted = scan as PatchArchiveScan.Accepted

        val cacheFree = context.cacheDir.usableSpace
        if (cacheFree in 1 until accepted.totalBytes + 64L * 1024L * 1024L) {
            refuse(
                work.archiveName,
                "The patch needs ${accepted.totalBytes} bytes of temporary space but only $cacheFree " +
                    "bytes are free in AGM's cache.",
            )
            return PasswordAttempt.Stopped
        }

        phase = JoiPlayExtractFlow.Phase.Extracting
        val workspace = File(context.cacheDir, "patch_stage_${System.currentTimeMillis()}")
        if (!workspace.mkdirs()) {
            fail("AGM could not create a staging folder for the patch.")
            return PasswordAttempt.Stopped
        }
        work.workspace = workspace
        val outcome = ArchiveExtractor.extract(
            context = context,
            archive = work.archive,
            format = work.format,
            password = password,
            destRoot = ArchiveExtractor.ExtractRoot.FileRoot(workspace),
            suggestedName = "staging",
            forcedSubfolderName = "staging",
        ) { update -> progress = update }
        when (outcome) {
            is ArchiveExtractor.Outcome.NeedsPassword -> {
                workspace.deleteRecursively()
                work.workspace = null
                return PasswordAttempt.Wrong
            }
            is ArchiveExtractor.Outcome.Cancelled -> {
                discardPending()
                clearInFlightState()
                return PasswordAttempt.Stopped
            }
            is ArchiveExtractor.Outcome.Failed -> {
                fail(outcome.message)
                return PasswordAttempt.Stopped
            }
            is ArchiveExtractor.Outcome.Ok -> Unit
        }
        val stagingRoot = (outcome as ArchiveExtractor.Outcome.Ok).rootFolder
            .let { it as? ArchiveExtractor.ExtractRoot.FileRoot }?.file
        if (stagingRoot == null) {
            fail("Patches must be staged on normal storage, not a document tree.")
            return PasswordAttempt.Stopped
        }
        work.stagingRoot = stagingRoot

        phase = JoiPlayExtractFlow.Phase.Preparing
        progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Checking the staged patch…")
        val verification = withContext(Dispatchers.IO) { StagedPatchVerifier.verify(stagingRoot, accepted) }
        if (verification is StagedPatchVerifier.Outcome.Rejected) {
            refuse(work.archiveName, verification.reason)
            return PasswordAttempt.Stopped
        }
        analyseStagedPatch(work, accepted, stagingRoot)
        return PasswordAttempt.Ok
    }

    private suspend fun analyseStagedPatch(
        work: Pending,
        scan: PatchArchiveScan.Accepted,
        stagingRoot: File,
    ) {
        progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Identifying the patch…")
        val evidence = withContext(Dispatchers.IO) {
            PatchEvidenceReader.read(work.archiveName, stagingRoot, scan)
        }
        val games = withContext(Dispatchers.IO) { ManagedGameStore(context).get() }
        if (games.isEmpty()) {
            refuse(work.archiveName, "There are no managed games in the AGM library to patch.")
            return
        }
        // Reading every game's .rpy source is only worth it when the patch itself carries no
        // options.rpy identity; otherwise one file per game is enough.
        val needsSourceScan = !evidence.hasDeclaredIdentity
        if (needsSourceScan) {
            progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Reading game scripts…")
        }
        val candidates = withContext(Dispatchers.IO) {
            games.map { RenPyGameProbe.probe(it, includeSourceSignatures = needsSourceScan) }
        }
        AppLog.i(
            "PatchInstall",
            "Identifying ${work.archiveName}: declaredIdentity=${evidence.hasDeclaredIdentity} " +
                "patchSignatures=${evidence.sourceSignatures.total} " +
                "(chars=${evidence.sourceSignatures.characters.size}, " +
                "labels=${evidence.sourceSignatures.labels.size}) candidates=${candidates.size}",
        )
        val identityLines = patchIdentityLines(evidence)
        val proven: ProvenTarget = when (val match = PatchTargetMatcher.match(evidence, candidates)) {
            is PatchMatchOutcome.Refused -> {
                refuse(work.archiveName, match.reason, identityLines)
                return
            }
            is PatchMatchOutcome.Matched -> ProvenTarget(
                target = match.target,
                proofs = match.proofs,
                compatibility = PatchCompatibility.Proven,
                compatibilityReason = null,
                installedVersion = match.target.provenVersion,
                patchVersionMarker = PatchSourceVersionEvidence
                    .highestMarker(evidence.sourceSignatures.labels),
            )
            is PatchMatchOutcome.Overridable -> ProvenTarget(
                target = match.target,
                proofs = match.proofs,
                compatibility = match.compatibility,
                compatibilityReason = match.reason,
                installedVersion = match.installedVersion,
                patchVersionMarker = match.patchVersionMarker,
            )
        }

        val game = games.first { it.id == proven.target.managedGameId }
        val existing = withContext(Dispatchers.IO) {
            runCatching { PatchTransactionStore.find(context, game.id) }.getOrNull()
        }
        if (existing != null) {
            refuse(
                work.archiveName,
                if (existing.isUnresolved) {
                    "${game.label} has an unresolved patch transaction (${existing.phase}). " +
                        "AGM will not patch it again until that is resolved."
                } else {
                    "${game.label} already has the patch '${existing.archiveName}' installed. " +
                        "Roll that patch back first if you want to install a different one."
                },
                identityLines,
            )
            return
        }
        val renPyRoot = proven.target.renPyRoot?.let(::File)
        if (renPyRoot == null) {
            refuse(work.archiveName, "AGM could not resolve ${game.label}'s Ren'Py folder.", identityLines)
            return
        }
        val destinationRoot = when (evidence.destination) {
            PatchDestination.GameFolder -> proven.target.gameFolder?.let(::File)
            PatchDestination.InstallRoot -> renPyRoot
        }
        if (destinationRoot == null) {
            refuse(work.archiveName, "AGM could not resolve ${game.label}'s game folder.", identityLines)
            return
        }
        val planned = withContext(Dispatchers.IO) {
            PatchInstallPlanner.plan(destinationRoot, scan.files)
        }
        if (planned is PatchPlanOutcome.Rejected) {
            refuse(work.archiveName, planned.reason, identityLines)
            return
        }
        val plan = (planned as PatchPlanOutcome.Ready).plan
        work.game = game
        work.plan = plan
        progress = null
        currentJob = null
        // Publishing a preview always drops any earlier acknowledgement: consent belongs to exactly
        // one target and one compatibility verdict.
        overrideAcknowledged = false
        preview = PatchInstallPreview(
            archiveName = work.archiveName,
            engineLabel = "Ren'Py",
            patchIdentityLines = identityLines,
            proofs = proven.proofs,
            targetManagedGameId = game.id,
            targetLabel = game.label,
            targetStoragePath = game.storagePath,
            targetIdentityLines = gameIdentityLines(proven.target),
            destinationRoot = plan.destinationRoot.absolutePath,
            additions = plan.additions.map { it.relativePath },
            replacements = plan.replacements.map { it.relativePath },
            totalBytes = plan.totalBytes,
            compatibility = proven.compatibility,
            compatibilityReason = proven.compatibilityReason,
            installedVersion = proven.installedVersion,
            patchVersionMarker = proven.patchVersionMarker,
            patchVersionHint = evidence.fileNameVersionHint,
        )
        if (proven.compatibility != PatchCompatibility.Proven) {
            AppLog.w(
                "PatchInstall",
                "Compatibility override offered for '${work.archiveName}': " +
                    "compatibility=${proven.compatibility} managedGameId=${game.id} " +
                    "label=${game.label} installedVersion=${proven.installedVersion ?: "unknown"} " +
                    "reason=${proven.compatibilityReason}",
            )
        }
    }

    /** A single managed game the analysis proved, plus how well the versions could be compared. */
    private data class ProvenTarget(
        val target: GameIdentityEvidence,
        val proofs: List<String>,
        val compatibility: PatchCompatibility,
        val compatibilityReason: String?,
        val installedVersion: String?,
        val patchVersionMarker: String?,
    )

    private fun patchIdentityLines(evidence: PatchEvidence): List<String> = buildList {
        add("Engine: ${if (evidence.engine == PatchEngine.RenPy) "Ren'Py" else "unrecognised"}")
        add(
            "Merges into: " + when (evidence.destination) {
                PatchDestination.GameFolder -> "<install>/game"
                PatchDestination.InstallRoot -> "<install>"
            },
        )
        evidence.optionsSource?.let { add("Patch metadata: $it") }
        evidence.options.configName?.let { add("Patch config.name: $it") }
        evidence.options.buildName?.let { add("Patch build.name: $it") }
        evidence.options.version?.let { add("Patch config.version: $it") }
        evidence.options.saveDirectory?.let { add("Patch config.save_directory: $it") }
        val signatures = evidence.sourceSignatures
        if (!signatures.isEmpty) {
            add(
                "Patch source signatures: ${signatures.characters.size} Character definition(s), " +
                    "${signatures.labels.size} label(s), ${signatures.declarations.size} declaration(s) " +
                    "from ${signatures.filesScanned} .rpy file(s)" +
                    if (signatures.truncated) " (scan capped)" else "",
            )
            PatchSourceVersionEvidence.highestMarker(signatures.labels)?.let {
                add("Highest structured version marker in the patch source: $it")
            }
        }
        evidence.fileNameVersionHint?.let { add("File-name version hint (not proof): $it") }
    }

    private fun gameIdentityLines(target: GameIdentityEvidence): List<String> = buildList {
        target.renPyRoot?.let { add("Ren'Py root: $it") }
        target.optionsSource?.let { add("Game metadata: $it") }
        target.options.configName?.let { add("Game config.name: $it") }
        target.provenBuildName?.let { add("Game build.name: $it") }
        target.options.version?.let { add("Game config.version: $it") }
        target.options.saveDirectory?.let { add("Game config.save_directory: $it") }
        if (!target.sourceSignatures.isEmpty) {
            add(
                "Game source signatures: ${target.sourceSignatures.characters.size} Character " +
                    "definition(s), ${target.sourceSignatures.labels.size} label(s) from " +
                    "${target.sourceSignatures.filesScanned} .rpy file(s)",
            )
        }
        if (target.layoutSignatures.isNotEmpty()) {
            add("Layout: ${target.layoutSignatures.joinToString(", ")}")
        }
        if (target.catalogVersionName.isNotBlank()) {
            add("AGM library version (supporting only): ${target.catalogVersionName}")
        }
    }

    private fun refuse(archive: String, reason: String, details: List<String> = emptyList()) {
        discardPending()
        preview = null
        overrideAcknowledged = false
        progress = null
        passwordPromptFor = null
        autoTryStatus = null
        currentJob = null
        archiveName = null
        cancellationAllowed = true
        refusal = PatchRefusalReport(archive, reason, details)
        AppLog.w("PatchInstall", "Refused $archive: $reason")
    }

    private fun fail(message: String) {
        discardPending()
        preview = null
        overrideAcknowledged = false
        progress = null
        passwordPromptFor = null
        autoTryStatus = null
        currentJob = null
        archiveName = null
        cancellationAllowed = true
        errorMessage = message
        AppLog.w("PatchInstall", message)
    }

    private fun clearInFlightState() {
        currentJob = null
        progress = null
        passwordPromptFor = null
        autoTryStatus = null
        savedPasswordsFailed = false
        lastPasswordWrong = false
        archiveName = null
        cancellationAllowed = true
        // Consent never outlives the preview it was given for.
        if (preview == null) overrideAcknowledged = false
    }

    /** Removes the cached archive copy and the staging workspace. The user's archive is untouched. */
    private fun discardPending() {
        val work = pending ?: return
        pending = null
        runCatching { work.archive.delete() }
        work.workspace?.let { workspace -> runCatching { workspace.deleteRecursively() } }
    }

    private class Pending(
        val archiveName: String,
        val archive: File,
        val format: ArchiveExtractor.Format,
    ) {
        var workspace: File? = null
        var stagingRoot: File? = null
        var game: ManagedGame? = null
        var plan: PatchInstallPlan? = null
    }
}
