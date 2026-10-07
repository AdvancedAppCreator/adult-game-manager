package com.example.f95updater

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * [JoiPlayExtractFlow]: the non-Android game install/extraction engine — a
 * copy-to-cache -> detect-format -> password -> extract state machine invoked from
 * MainActivity.
 *
 * The flow:
 *   1. User picks a compressed archive (SAF picker, biased to source folder if set)
 *   2. We copy it to cache, sniff format, ask for password if needed
 *   3. We extract to destination (SAF tree or File depending on storage strategy)
 *   4. We show our own folder browser of the extracted result, highlighting likely
 *      launch files (Game.exe, index.html, script.rpy, *.swf, *.py, *.sh)
 *   5. The user taps one — AGM validates the folder and stores its selected runner bindings
 *
 * The JoiPlay/archive extraction UI composables and pure helpers that drive this flow
 * live in sibling files: JoiPlayInstallWarningDialog, JoiPlaySettingsDialogUi,
 * JoiPlayProgressPasswordDialogs, JoiPlayFileBrowserUi, JoiPlayFileTree, and
 * JoiPlayStorageAccess.
 */

// ---------------------------- ORCHESTRATOR ----------------------------

class JoiPlayExtractFlow(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    enum class Phase { Preparing, Extracting, Cancelling }
    var archiveName by mutableStateOf<String?>(null); private set
    var phase by mutableStateOf(Phase.Preparing); private set
    var progress by mutableStateOf<ArchiveExtractor.Progress?>(null); private set
    var passwordPromptFor by mutableStateOf<ArchiveExtractor.Format?>(null); private set
    var autoTryStatus by mutableStateOf<String?>(null); private set
    var savedPasswordsFailed by mutableStateOf(false); private set
    var lastPasswordWrong by mutableStateOf(false); private set
    var extractedRoot by mutableStateOf<ArchiveExtractor.ExtractRoot?>(null); private set
    var errorMessage by mutableStateOf<String?>(null); private set
    private var pendingArchive: File? = null
    private var pendingDestRoot: ArchiveExtractor.ExtractRoot? = null
    private var sourceArchiveFile: File? = null
    private var cleanupDestinationOnAbort = false
    private var intermediateRootToDelete: ArchiveExtractor.ExtractRoot? = null
    private var currentJob: Job? = null
    /**
     * Bumped by every accepted start. A job whose generation is stale lost the pipeline (it was
     * cancelled and replaced), so it must not delete files or clear state that now belongs to the
     * job that succeeded it.
     */
    private var generation = 0L

    /** True while *anything* is in flight (copying source, extracting). UI uses this to
     *  decide whether to show the progress dialog. */
    val inProgress: Boolean
        get() = progress != null || (phase == Phase.Cancelling && archiveName != null)

    /**
     * True while this flow owns the install pipeline: something is running, or a result/error/
     * password prompt is still waiting for the user and still holds the cached archive and
     * destination. Starting anything else in that window would walk over both.
     */
    val busy: Boolean
        get() = inProgress ||
            extractedRoot != null ||
            passwordPromptFor != null ||
            errorMessage != null

    val activeOperation: InstallOperationOwner?
        get() = if (!busy) {
            null
        } else {
            InstallOperationOwner(
                kind = InstallOperationKind.Extract,
                detail = archiveName,
                cancellable = inProgress,
                awaitingUser = !inProgress,
            )
        }

    /**
     * Begins an extraction, or refuses when this flow is already busy.
     *
     * The refusal is the whole point: the previous behaviour cancelled whatever was running and
     * overwrote `pendingArchive`/`pendingDestRoot`, so the cancelled job's cleanup then deleted the
     * *new* job's cached archive and destination folder.
     */
    fun start(
        archiveUri: Uri,
        destRoot: ArchiveExtractor.ExtractRoot,
        deleteSourceOnSuccess: File? = null,
        cleanupDestinationOnAbort: Boolean = false,
        intermediateRootToDeleteAfterCopy: ArchiveExtractor.ExtractRoot? = null,
    ): InstallStartOutcome {
        InstallOperationGuard.refuse(activeOperation, InstallOperationKind.Extract)?.let { reason ->
            AppLog.w("Extract", "Refused a second extraction: $reason")
            return InstallStartOutcome.Refused(reason)
        }
        val gen = ++generation
        sourceArchiveFile = deleteSourceOnSuccess
        pendingDestRoot = destRoot
        this.cleanupDestinationOnAbort = cleanupDestinationOnAbort
        intermediateRootToDelete = intermediateRootToDeleteAfterCopy
        val quickName = archiveUri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "archive"
        archiveName = quickName
        phase = Phase.Preparing
        progress = ArchiveExtractor.Progress(
            bytesWritten = 0L,
            bytesTotal = 0L,
            entriesProcessed = 0,
            entriesTotal = 1,
            currentEntry = "Preparing $quickName…",
        )
        currentJob = scope.launch {
            try {
                val name = ArchiveUriSource.displayName(context, archiveUri).ifBlank { "archive" }
                archiveName = name
                // Phase 1: copy SAF source to cache, with progress.
                phase = Phase.Preparing
                val totalBytes = ArchiveUriSource.sizeBytes(context, archiveUri)
                progress = ArchiveExtractor.Progress(
                    bytesWritten = 0L,
                    bytesTotal = totalBytes,
                    entriesProcessed = 0,
                    entriesTotal = 1,
                    currentEntry = "Reading $name…",
                )
                val cached = ArchiveUriSource.copyToCache(context, archiveUri, name, "extract_in_") { written ->
                    progress = ArchiveExtractor.Progress(
                        bytesWritten = written,
                        bytesTotal = if (totalBytes > 0) totalBytes else written,
                        entriesProcessed = 0,
                        entriesTotal = 1,
                        currentEntry = "Reading $name…",
                    )
                }
                cleanupIntermediateRoot()
                val format = detectFormat(cached)
                if (format == null) {
                    cached.delete()
                    progress = null
                    errorMessage = "Unknown archive format. Supported: ZIP, RAR, 7Z."
                    archiveName = null
                    cleanupPendingDestination()
                    return@launch
                }
                pendingArchive = cached
                // Phase 2: extract.
                phase = Phase.Extracting
                val outcome = runExtraction(cached, format, destRoot, password = null, suggestedName = name.substringBeforeLast('.'))
                if (outcome is ArchiveExtractor.Outcome.NeedsPassword) onNeedsPasswordInitial(outcome.format)
            } catch (ce: CancellationException) {
                AppLog.i("Extract", "Cancelled")
                if (owns(gen)) {
                    cleanupIntermediateRoot()
                    cleanupPendingDestination()
                    clearInFlightState()
                }
            } catch (t: Throwable) {
                if (owns(gen)) {
                    progress = null
                    errorMessage = "Could not start extraction: ${t.message}"
                    archiveName = null
                    cleanupIntermediateRoot()
                    cleanupPendingDestination()
                }
            }
        }
        return InstallStartOutcome.Started
    }

    /** True while [gen] is still the generation that owns this flow's files and state. */
    private fun owns(gen: Long): Boolean {
        if (gen == generation) return true
        AppLog.w("Extract", "Stale extraction job gen=$gen ignored (current=$generation)")
        return false
    }


    fun submitPassword(password: String) {
        val arc = pendingArchive ?: return
        val dest = pendingDestRoot ?: return
        val name = archiveName ?: "archive"
        val gen = generation
        passwordPromptFor = null
        savedPasswordsFailed = false
        lastPasswordWrong = false
        phase = Phase.Preparing
        progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Preparing $name…")
        currentJob = scope.launch {
            try {
                val format = detectFormat(arc) ?: return@launch
                phase = Phase.Extracting
                val outcome = runExtraction(arc, format, dest, password = password.toCharArray(), suggestedName = name.substringBeforeLast('.'))
                when (outcome) {
                    is ArchiveExtractor.Outcome.Ok -> PasswordVault.remember(password)
                    is ArchiveExtractor.Outcome.NeedsPassword -> {
                        // Wrong password — re-open the prompt with a hint.
                        lastPasswordWrong = true
                        passwordPromptFor = outcome.format
                    }
                    else -> { /* Failed/Cancelled already surfaced by runExtraction */ }
                }
            } catch (ce: CancellationException) {
                AppLog.i("Extract", "Cancelled")
                if (owns(gen)) {
                    cleanupIntermediateRoot()
                    cleanupPendingDestination()
                    clearInFlightState()
                }
            }
        }
    }

    private fun onNeedsPasswordInitial(format: ArchiveExtractor.Format) {
        lastPasswordWrong = false
        savedPasswordsFailed = false
        if (PasswordVault.isEmpty()) {
            passwordPromptFor = format
        } else {
            autoTryAll(format)
        }
    }

    private fun autoTryAll(format: ArchiveExtractor.Format) {
        val arc = pendingArchive ?: return
        val dest = pendingDestRoot ?: return
        val name = archiveName ?: "archive"
        val gen = generation
        val candidates = PasswordVault.all()
        phase = Phase.Preparing
        progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Preparing $name…")
        currentJob = scope.launch {
            try {
                for ((i, pwd) in candidates.withIndex()) {
                    if (!isActive) return@launch
                    autoTryStatus = "Trying saved password ${i + 1}/${candidates.size}…"
                    phase = Phase.Extracting
                    val outcome = runExtraction(arc, format, dest, password = pwd.toCharArray(), suggestedName = name.substringBeforeLast('.'))
                    when (outcome) {
                        is ArchiveExtractor.Outcome.Ok -> {
                            PasswordVault.remember(pwd)
                            autoTryStatus = null
                            return@launch
                        }
                        is ArchiveExtractor.Outcome.NeedsPassword -> { /* try next */ }
                        else -> { autoTryStatus = null; return@launch } // Failed/Cancelled surfaced already
                    }
                }
                // No saved password worked — fall back to manual entry.
                autoTryStatus = null
                savedPasswordsFailed = true
                passwordPromptFor = format
            } catch (ce: CancellationException) {
                AppLog.i("Extract", "Cancelled")
                if (owns(gen)) {
                    cleanupIntermediateRoot()
                    cleanupPendingDestination()
                    clearInFlightState()
                }
            }
        }
    }

    fun cancelInProgress() {
        val job = currentJob
        if (job?.isActive == true) {
            phase = Phase.Cancelling
            progress = progress ?: ArchiveExtractor.Progress(0L, 0L, 0, 1, "Cancelling…")
            passwordPromptFor = null
            autoTryStatus = null
            job.cancel()
            return
        }
        cleanupIntermediateRoot()
        cleanupPendingDestination()
        clearInFlightState()
    }

    private fun cleanupIntermediateRoot() {
        val root = intermediateRootToDelete ?: return
        intermediateRootToDelete = null
        val deleted = runCatching {
            when (root) {
                is ArchiveExtractor.ExtractRoot.FileRoot -> root.file.deleteRecursively()
                is ArchiveExtractor.ExtractRoot.Saf -> root.doc.delete()
            }
        }.getOrDefault(false)
        if (!deleted) {
            AppLog.w("Extract", "Could not delete intermediate archive wrapper ${root.displayPath}")
        }
    }

    private fun cleanupPendingDestination() {
        if (!cleanupDestinationOnAbort) return
        val root = pendingDestRoot as? ArchiveExtractor.ExtractRoot.FileRoot
        root?.file?.deleteRecursively()
        pendingDestRoot = null
        cleanupDestinationOnAbort = false
    }

    private fun clearInFlightState() {
        pendingArchive?.delete()
        pendingArchive = null
        currentJob = null
        progress = null
        archiveName = null
        passwordPromptFor = null
        autoTryStatus = null
        savedPasswordsFailed = false
        lastPasswordWrong = false
        sourceArchiveFile = null
        intermediateRootToDelete = null
    }

    fun acknowledgeError() {
        pendingArchive?.delete()
        pendingArchive = null
        errorMessage = null
    }

    fun acknowledgeResult() {
        extractedRoot = null
        archiveName = null
        progress = null
        pendingArchive?.delete()
        pendingArchive = null
        pendingDestRoot = null
        cleanupDestinationOnAbort = false
    }

    private fun detectFormat(file: File): ArchiveExtractor.Format? {
        val byExt = ArchiveExtractor.detectFormatByExt(file.name)
        if (byExt != null) return byExt
        return file.inputStream().use { ArchiveExtractor.detectFormat(it) }
    }

    private suspend fun runExtraction(
        archive: File,
        format: ArchiveExtractor.Format,
        destRoot: ArchiveExtractor.ExtractRoot,
        password: CharArray?,
        suggestedName: String,
    ): ArchiveExtractor.Outcome {
        val outcome = try {
            ArchiveExtractor.extract(
                context, archive, format, password, destRoot, suggestedName,
            ) { p -> progress = p }
        } catch (ce: CancellationException) {
            clearInFlightState()
            throw ce
        }
        when (outcome) {
            is ArchiveExtractor.Outcome.Ok -> {
                progress = null
                extractedRoot = outcome.rootFolder
                cleanupDestinationOnAbort = false
                AppLog.i("Extract", "Extracted ${outcome.bytesWritten} bytes to ${outcome.rootFolder.displayPath}")
                // If the caller asked to delete the source archive on success, do it now.
                sourceArchiveFile?.let { src ->
                    runCatching {
                        if (src.exists() && src.delete()) {
                            AppLog.i("Extract", "Deleted source archive ${src.absolutePath}")
                        } else {
                            AppLog.w("Extract", "Could not delete source archive ${src.absolutePath}")
                        }
                    }
                }
                sourceArchiveFile = null
            }
            is ArchiveExtractor.Outcome.NeedsPassword -> {
                // Caller decides whether to prompt, offer a choice, or try the next
                // saved password. Just clear the progress meter here.
                progress = null
            }
            is ArchiveExtractor.Outcome.Failed -> {
                progress = null
                errorMessage = outcome.message
                archiveName = null
                cleanupPendingDestination()
                AppLog.w("Extract", "Failed: ${outcome.message}", outcome.cause)
            }
            is ArchiveExtractor.Outcome.Cancelled -> {
                progress = null
                archiveName = null
                currentJob = null
                cleanupPendingDestination()
                AppLog.i("Extract", "Cancelled")
            }
        }
        return outcome
    }
}
