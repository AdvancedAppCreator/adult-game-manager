package com.example.f95updater

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.junrar.Archive
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.json.JSONObject
import java.io.File

data class ManagedArchiveAnalysis(
    val entryCount: Int,
    val entryNames: List<String> = emptyList(),
    val nameHints: List<ManagedNameHint> = emptyList(),
)

data class ManagedUpgradePrompt(
    val archive: File,
    val matches: List<ManagedUpgradeCandidate>,
    val installAsNewRoute: InstallRouting.ArchiveRoute,
)

data class ManagedUpgradeResult(
    val managedGameId: String,
    val label: String,
    val newFolder: String,
    val oldFolder: String,
    val oldFolderRenamed: Boolean,
    val saveItemsCopied: Int,
    val sourceArchiveName: String?,
    /**
     * What became of the archive this upgrade installed from. Replaces the old
     * deleted/deleteRequested boolean pair, which could not distinguish an archive AGM failed to
     * delete from one that was already gone.
     */
    val sourceArchiveOutcome: ManagedUpgradeSourceArchiveOutcome =
        ManagedUpgradeSourceArchiveOutcome.DEFAULT,
    val warnings: List<String>,
)

object ManagedArchiveInspector {

    /** Metadata files an engine writes for itself, so their names are the game's own claim. */
    private val metadataFileNames = setOf("options.rpy", "package.json", "game.ini")

    /** Bounds on the metadata read: a classification pass must never stream a whole archive. */
    private const val MAX_METADATA_FILES = 8
    private const val MAX_METADATA_BYTES = 128L * 1024L

    suspend fun analyze(archive: File): ManagedArchiveAnalysis = withContext(Dispatchers.IO) {
        val format = ArchiveExtractor.detectFormatByExt(archive.name)
            ?: archive.inputStream().use { ArchiveExtractor.detectFormat(it) }
        val entries = when (format) {
            ArchiveExtractor.Format.ZIP -> zipEntries(archive)
            ArchiveExtractor.Format.RAR -> rarEntries(archive)
            ArchiveExtractor.Format.SEVENZ -> sevenZEntries(archive)
            null -> emptyList()
        }.map { it.replace('\\', '/').trimStart('/') }.filter { it.isNotBlank() }

        val hints = buildList {
            addAll(metadataHints(archive, format))
            commonRootFolder(entries)?.let {
                add(ManagedNameHint(cleanCandidateName(it), ManagedNameEvidence.Structure, "folder \u201C$it\u201D"))
            }
            launcherHint(entries)?.let { add(it) }
            add(
                ManagedNameHint(
                    cleanCandidateName(archive.nameWithoutExtension),
                    ManagedNameEvidence.ArchiveName,
                    archive.name,
                ),
            )
        }.filter { it.value.length >= 3 }
            .distinctBy { it.evidence to CatalogRepository.normalizeTitle(it.value) }

        ManagedArchiveAnalysis(
            entryCount = entries.size,
            entryNames = entries,
            nameHints = hints,
        )
    }

    /**
     * Installed managed games this archive *proved* it updates. Nothing here scores or guesses: see
     * [ManagedUpgradeIdentity.candidates] for the evidence rules.
     */
    fun findUpgradeMatches(
        analysis: ManagedArchiveAnalysis,
        apps: List<InstalledApp>,
    ): List<ManagedUpgradeCandidate> = ManagedUpgradeIdentity.candidates(analysis.nameHints, apps)

    private fun zipEntries(archive: File): List<String> =
        ZipFile(archive).fileHeaders.map { it.fileName }

    private fun rarEntries(archive: File): List<String> {
        if (UnrarNative.available) {
            return runCatching { UnrarNative.listEntries(archive, null).map { it.name } }.getOrDefault(emptyList())
        }
        return runCatching {
            Archive(archive).use { rar ->
                val out = mutableListOf<String>()
                var h = rar.nextFileHeader()
                while (h != null) {
                    out += h.fileNameString
                    h = rar.nextFileHeader()
                }
                out
            }
        }.getOrDefault(emptyList())
    }

    private fun sevenZEntries(archive: File): List<String> =
        runCatching {
            SevenZFile(archive).use { sz ->
                val out = mutableListOf<String>()
                var e = sz.nextEntry
                while (e != null) {
                    out += e.name
                    e = sz.nextEntry
                }
                out
            }
        }.getOrDefault(emptyList())

    /** Metadata names, read straight out of the container for every format AGM installs. */
    private fun metadataHints(
        archive: File,
        format: ArchiveExtractor.Format?,
    ): List<ManagedNameHint> = runCatching {
        when (format) {
            ArchiveExtractor.Format.ZIP -> zipMetadataTexts(archive)
            ArchiveExtractor.Format.RAR -> rarMetadataTexts(archive)
            ArchiveExtractor.Format.SEVENZ -> sevenZMetadataTexts(archive)
            null -> emptyList()
        }
    }.getOrElse {
        AppLog.i("ManagedUpgrade", "No readable metadata in ${archive.name}: ${it.message}")
        emptyList()
    }.flatMap { (path, text) -> metadataNames(path, text) }

    private fun isMetadataCandidate(name: String, size: Long, directory: Boolean): Boolean {
        if (directory || size > MAX_METADATA_BYTES) return false
        return name.replace('\\', '/').substringAfterLast('/').lowercase() in metadataFileNames
    }

    private fun zipMetadataTexts(archive: File): List<Pair<String, String>> {
        val zip = ZipFile(archive)
        if (zip.isEncrypted) return emptyList()
        return zip.fileHeaders
            .filter { isMetadataCandidate(it.fileName, it.uncompressedSize, it.isDirectory) }
            .take(MAX_METADATA_FILES)
            .mapNotNull { header ->
                runCatching {
                    zip.getInputStream(header).bufferedReader().use { it.readText() }
                }.getOrNull()?.let { header.fileName to it }
            }
    }

    private fun rarMetadataTexts(archive: File): List<Pair<String, String>> =
        Archive(archive).use { rar ->
            if (rar.isEncrypted) return emptyList()
            val out = mutableListOf<Pair<String, String>>()
            var header = rar.nextFileHeader()
            while (header != null && out.size < MAX_METADATA_FILES) {
                val current = header
                if (isMetadataCandidate(current.fileNameString, current.fullUnpackSize, current.isDirectory)) {
                    runCatching {
                        rar.getInputStream(current).bufferedReader().use { it.readText() }
                    }.getOrNull()?.let { out += current.fileNameString to it }
                }
                header = rar.nextFileHeader()
            }
            out
        }

    private fun sevenZMetadataTexts(archive: File): List<Pair<String, String>> =
        SevenZFile(archive).use { sevenZ ->
            val out = mutableListOf<Pair<String, String>>()
            var entry = sevenZ.nextEntry
            while (entry != null && out.size < MAX_METADATA_FILES) {
                val current = entry
                if (isMetadataCandidate(current.name.orEmpty(), current.size, current.isDirectory)) {
                    val buffer = ByteArray(current.size.toInt().coerceAtLeast(0))
                    runCatching {
                        var read = 0
                        while (read < buffer.size) {
                            val n = sevenZ.read(buffer, read, buffer.size - read)
                            if (n < 0) break
                            read += n
                        }
                        String(buffer, 0, read, Charsets.UTF_8)
                    }.getOrNull()?.let { out += current.name.orEmpty() to it }
                }
                entry = sevenZ.nextEntry
            }
            out
        }

    private fun metadataNames(path: String, text: String): List<ManagedNameHint> {
        val base = path.replace('\\', '/').substringAfterLast('/').lowercase()
        val values = when (base) {
            "package.json" -> runCatching {
                val json = JSONObject(text)
                listOf(json.optString("title"), json.optString("name"))
            }.getOrDefault(emptyList())
            "game.ini" -> listOfNotNull(
                Regex("""(?im)^\s*(?:title|name)\s*=\s*(.+?)\s*$""").find(text)?.groupValues?.getOrNull(1),
            )
            "options.rpy" -> listOfNotNull(
                RenPyOptionsParser.parse(text).configName,
                RenPyOptionsParser.parse(text).buildName,
            )
            else -> emptyList()
        }
        return values
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { ManagedNameHint(cleanCandidateName(it), ManagedNameEvidence.Metadata, path) }
    }

    /**
     * A Ren'Py build writes `<build.name>.py` beside `<build.name>.sh`/`.exe`, so a matched pair is
     * the archive's own structural claim about its identity. A lone launch file is not.
     */
    private fun launcherHint(entries: List<String>): ManagedNameHint? {
        val names = entries.map { it.substringAfterLast('/') }.filter { it.isNotBlank() }
        val pythonBases = names
            .filter { it.substringAfterLast('.', "").equals("py", ignoreCase = true) }
            .map { it.substringBeforeLast('.') }
            .filter { it.isNotBlank() }
            .distinct()
        val confirmed = pythonBases.filter { base ->
            names.any { candidate ->
                candidate.equals("$base.sh", ignoreCase = true) ||
                    candidate.equals("$base.exe", ignoreCase = true) ||
                    candidate.equals("$base-32.exe", ignoreCase = true)
            }
        }
        val single = confirmed.singleOrNull() ?: return null
        return ManagedNameHint(
            cleanCandidateName(single),
            ManagedNameEvidence.Structure,
            "Ren'Py launcher \u201C$single\u201D",
        )
    }

    private fun commonRootFolder(names: List<String>): String? {
        if (names.isEmpty()) return null
        val firstSegments = names.map { it.trimStart('/').substringBefore('/') }
        val unique = firstSegments.toSet()
        if (unique.size != 1) return null
        val candidate = unique.single()
        if (candidate.isBlank()) return null
        return if (names.any { it.contains('/') }) candidate else null
    }

    private fun cleanCandidateName(raw: String): String =
        raw.substringAfterLast('/')
            .substringBeforeLast('.', raw)
            .replace(Regex("""[_\-.]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
}

/** Decides whether a classified archive should offer an upgrade of an installed managed game. */
object ManagedArchiveUpgradeCoordinator {
    sealed interface Decision {
        data class Upgrade(val matches: List<ManagedUpgradeCandidate>) : Decision
        data object InstallAsNew : Decision
    }

    fun decide(
        route: InstallRouting.ArchiveRoute,
        analysis: ManagedArchiveAnalysis,
        apps: List<InstalledApp>,
    ): Decision {
        if (!InstallRouting.isManagedUpgradeEligible(route)) return Decision.InstallAsNew
        val matches = ManagedArchiveInspector.findUpgradeMatches(analysis, apps)
        return when (InstallRouting.routeUpgradeInspection(matches)) {
            InstallRouting.UpgradeInspectionRoute.ShowUpgradePrompt -> Decision.Upgrade(matches)
            InstallRouting.UpgradeInspectionRoute.ExtractAsNewInstall -> Decision.InstallAsNew
        }
    }
}

/**
 * A Winlator re-point the UI still has to launch.
 *
 * [generation] is the upgrade that owns it: a cancellation may only withdraw a request published by
 * its own generation, and only while it has not been claimed for launching yet.
 */
data class ManagedUpgradeWinlatorRequest(
    val sequence: Long,
    val generation: Long,
    val intent: Intent,
)

/**
 * Upgrades an already installed managed game from an archive.
 *
 * The old folder is never touched while the archive extracts: the archive lands in a brand new
 * uniquely named sibling folder, saves are copied and verified into it, then the AGM record and
 * every runner are re-pointed at it. Only after that commit is the previous folder renamed aside
 * for the user to delete. Each step past extraction is journalled in [ManagedUpgradePendingStore]
 * so process death is recoverable.
 */
class ManagedArchiveUpgradeFlow(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var archiveName by mutableStateOf<String?>(null); private set
    var phase by mutableStateOf(JoiPlayExtractFlow.Phase.Preparing); private set
    var progress by mutableStateOf<ArchiveExtractor.Progress?>(null); private set
    var passwordPromptFor by mutableStateOf<ArchiveExtractor.Format?>(null); private set
    var autoTryStatus by mutableStateOf<String?>(null); private set
    var savedPasswordsFailed by mutableStateOf(false); private set
    var lastPasswordWrong by mutableStateOf(false); private set
    var errorMessage by mutableStateOf<String?>(null); private set
    var result by mutableStateOf<ManagedUpgradeResult?>(null); private set
    var winlatorRequest by mutableStateOf<ManagedUpgradeWinlatorRequest?>(null); private set

    /**
     * True only while a cancellation can still be *observed and fully undone*.
     *
     * It is flipped to false at [ManagedUpgradeCancellation.boundaryStep], before the first write
     * the installed game will be resumed from, so the progress dialog's and the minimized card's
     * Cancel disappear in the same recomposition that starts that step.
     */
    var cancellationAllowed by mutableStateOf(true); private set

    /**
     * The non-success outcome of an accepted cancellation. Not an error: nothing was changed. It is
     * kept until acknowledged so the flow still owns the install pipeline (and a bulk run still has
     * something to settle on) exactly like a result or an error does.
     */
    var cancellationNotice by mutableStateOf<String?>(null); private set

    /**
     * Test seam: notified on the flow's own coroutine as each post-extraction step starts, so a test
     * can press Cancel at an exact step instead of racing a timer. Never set in production.
     */
    @VisibleForTesting
    internal var stepListener: ((ManagedUpgradeStep) -> Unit)? = null

    /**
     * Test seam: notified on the flow's own coroutine right after a Winlator request is published
     * and before the flow suspends again, so a race test can cancel — or claim the request the way
     * the Activity does — at that exact instant. Never set in production.
     */
    @VisibleForTesting
    internal var winlatorRequestListener: ((ManagedUpgradeWinlatorRequest) -> Unit)? = null

    /**
     * Test seam: replaces the Winlator capability preflight so a race test can reach the publish on
     * a device without Winlator installed. Never set in production.
     */
    @VisibleForTesting
    internal var winlatorCapabilityCheck: (suspend () -> Result<Unit>)? = null

    private var currentJob: Job? = null
    private var pending: Pending? = null
    /**
     * Bumped by every accepted start. The pending-upgrade journal is a single durable record shared
     * by every upgrade, so a job that lost the pipeline must never save or clear it: that is exactly
     * how an aborted old upgrade used to erase a newer upgrade's recovery record.
     */
    private var generation = 0L

    /**
     * Guards the whole Winlator hand-off: the published request, its sequence and the record of
     * what has already been handed to the Activity launcher.
     *
     * Publishing happens on the flow's IO coroutine while claiming happens on the main thread, so
     * "is this request still mine to withdraw?" has to be decided under a lock. Without it a
     * cancellation could delete the new folder in the same instant the launcher fires the intent
     * that tells Winlator to run the game from it.
     */
    private val winlatorHandoffLock = Any()
    private var winlatorSequence = 0L
    private var winlatorLaunchedSequence = 0L
    private var winlatorLaunchedGeneration = 0L

    /** What a cancellation found when it tried to take back this generation's Winlator request. */
    private enum class WinlatorHandoff {
        /** Nothing was published: the cancellation owns the folders and can undo everything. */
        None,

        /** The request was still queued and has been withdrawn, so it can never be launched. */
        Withdrawn,

        /** Winlator has (or may have) the request: nothing may be deleted and the journal stays. */
        Launched,
    }

    val inProgress: Boolean
        get() = progress != null || (phase == JoiPlayExtractFlow.Phase.Cancelling && archiveName != null)

    val busy: Boolean
        get() = inProgress ||
            passwordPromptFor != null ||
            errorMessage != null ||
            cancellationNotice != null ||
            result != null ||
            winlatorRequest != null

    val activeOperation: InstallOperationOwner?
        get() = if (!busy) {
            null
        } else {
            InstallOperationOwner(
                kind = InstallOperationKind.Upgrade,
                detail = archiveName,
                // A Winlator round-trip and the store relocation are not interruptible.
                cancellable = cancellationAllowed && winlatorRequest == null,
                awaitingUser = !inProgress && winlatorRequest == null,
            )
        }

    /**
     * Begins an upgrade, or refuses when this flow is already busy.
     *
     * It used to call [cancelInProgress] first, which is a no-op while the commit or the Winlator
     * round-trip holds `cancellationAllowed = false` — so the second start simply overwrote
     * `pending` and the journal while the first upgrade was mid-flight.
     */
    fun start(archive: File, app: InstalledApp): InstallStartOutcome {
        InstallOperationGuard.refuse(activeOperation, InstallOperationKind.Upgrade)?.let { reason ->
            AppLog.w("ManagedUpgrade", "Refused a second upgrade: $reason")
            return InstallStartOutcome.Refused(reason)
        }
        // The journal is a single durable record. If one is still on disk here it belongs to an
        // upgrade AGM has not settled (an abandoned/inconsistent one, or one interrupted by process
        // death), and overwriting it would orphan that upgrade's folder for good.
        abandonedJournalRefusal()?.let { reason ->
            AppLog.w("ManagedUpgrade", "Refused an upgrade over an unresolved journal: $reason")
            return InstallStartOutcome.Refused(reason)
        }
        val gen = ++generation
        synchronized(winlatorHandoffLock) {
            winlatorRequest = null
            winlatorLaunchedSequence = 0L
            winlatorLaunchedGeneration = 0L
        }
        cancellationAllowed = true
        cancellationNotice = null
        archiveName = archive.name
        phase = JoiPlayExtractFlow.Phase.Preparing
        progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Preparing ${archive.name}…")
        currentJob = scope.launch {
            try {
                prepareAndExtract(archive, app, gen)
            } catch (ce: CancellationException) {
                AppLog.i("ManagedUpgrade", "Cancelled")
                acceptCancellation(gen)
            }
        }
        return InstallStartOutcome.Started
    }

    /** Why a new upgrade may not start over an unresolved recovery record, or null when it may. */
    private fun abandonedJournalRefusal(): String? {
        val abandoned = runCatching { ManagedUpgradePendingStore.load(context) }.getOrNull() ?: return null
        return "AGM still holds an unfinished update of ${abandoned.label} (${abandoned.newRootPath}). " +
            "Restart AGM so it can finish or discard that update before starting another one."
    }

    /** True while [gen] is still the generation that owns this flow's folders and journal. */
    private fun owns(gen: Long): Boolean {
        if (gen == generation) return true
        AppLog.w("ManagedUpgrade", "Stale upgrade job gen=$gen ignored (current=$generation)")
        return false
    }

    /** Journal writes are ownership-checked: a stale job must not touch the live recovery record. */
    private fun saveJournal(work: Pending, state: ManagedUpgradePendingState) {
        if (!owns(work.generation)) return
        ManagedUpgradePendingStore.save(context, state)
    }

    private fun clearJournal(work: Pending) {
        if (!owns(work.generation)) return
        ManagedUpgradePendingStore.clear(context)
    }

    /** Announces the step that is about to run. Production has no listener; tests cancel here. */
    private fun notifyStep(step: ManagedUpgradeStep) {
        stepListener?.invoke(step)
    }

    /**
     * The explicit cancellation boundary.
     *
     * Everything before this point only reads the installed game, so a cancel can be undone by
     * deleting the new folder and the journal. Everything after it writes state the game will be
     * resumed from and ends in a `NonCancellable` store relocation, so Cancel must be gone from the
     * dialog *and* the minimized card before the next step starts.
     */
    private fun crossCancellationBoundary(work: Pending) {
        if (!owns(work.generation)) return
        cancellationAllowed = false
        AppLog.i(
            "ManagedUpgrade",
            "Cancellation boundary crossed for ${work.game.label}; the upgrade is now committed",
        )
    }

    /**
     * A cancellation checkpoint for the still-cancellable half of the upgrade.
     *
     * The discovery, the binding plan and the journal write are ordinary blocking calls, so a
     * cancelled job would otherwise sail past them and only be noticed at the next suspension point
     * — which is either inside the `NonCancellable` commit (a silent commit after an accepted
     * cancel) or inside the Winlator capability round-trip (a stale journal plus an orphaned
     * folder). Returns true when the cancellation was accepted and fully cleaned up.
     */
    private suspend fun checkpoint(work: Pending): Boolean {
        if (currentCoroutineContext().isActive) return false
        acceptCancellation(work.generation, work)
        return true
    }

    /**
     * Publishes a Winlator re-point for the Activity to launch, or refuses.
     *
     * A request is only published while [work]'s generation still owns the flow, the job has not
     * been cancelled and the cancellation boundary has already been crossed. Anything else means a
     * cancellation owns the new folder, and handing Winlator a path AGM is about to delete is the
     * one outcome that cannot be recovered from.
     */
    private fun publishWinlatorRequest(work: Pending, intent: Intent): ManagedUpgradeWinlatorRequest? =
        synchronized(winlatorHandoffLock) {
            if (!owns(work.generation)) return null
            if (cancellationAllowed) {
                AppLog.w("ManagedUpgrade", "Refused a Winlator re-point before the commit boundary")
                return null
            }
            winlatorSequence++
            val request = ManagedUpgradeWinlatorRequest(
                sequence = winlatorSequence,
                generation = work.generation,
                intent = intent,
            )
            winlatorRequest = request
            request
        }

    /**
     * Takes the published intent, exactly once, for launching.
     *
     * Returns null when the request was withdrawn by a cancellation or superseded, so a launcher
     * that woke up a moment too late can never re-point Winlator at a folder AGM already deleted.
     */
    fun claimWinlatorRequest(sequence: Long): Intent? = synchronized(winlatorHandoffLock) {
        val request = winlatorRequest
        if (request == null || request.sequence != sequence) {
            AppLog.w("ManagedUpgrade", "Ignored a withdrawn Winlator request seq=$sequence")
            return null
        }
        winlatorRequest = null
        winlatorLaunchedSequence = request.sequence
        winlatorLaunchedGeneration = request.generation
        AppLog.i("ManagedUpgrade", "Winlator request seq=${request.sequence} claimed for launch")
        request.intent
    }

    /** Takes back [gen]'s Winlator request if — and only if — nobody has claimed it for launching. */
    private fun withdrawWinlatorRequest(gen: Long): WinlatorHandoff = synchronized(winlatorHandoffLock) {
        if (winlatorLaunchedGeneration == gen && winlatorLaunchedSequence != 0L) {
            return WinlatorHandoff.Launched
        }
        val request = winlatorRequest ?: return WinlatorHandoff.None
        // A request from another generation is not this cancellation's to resolve; treat the
        // hand-off as uncertain rather than deleting folders somebody else still owns.
        if (request.generation != gen) return WinlatorHandoff.Launched
        winlatorRequest = null
        WinlatorHandoff.Withdrawn
    }

    /**
     * Accepts a cancellation: deletes the new folder, clears *this generation's* journal, drops the
     * pending record and surfaces a non-success cancellation message.
     *
     * Runs entirely under [NonCancellable] because it is called from an already cancelled coroutine;
     * the whole point is that nothing here may be skipped. The source archive and the installed
     * folder are never touched. A cancellation that lost the race with the Winlator hand-off deletes
     * nothing at all — see [retainAfterWinlatorHandoff].
     */
    private suspend fun acceptCancellation(gen: Long, work: Pending? = null) {
        withContext(NonCancellable) {
            if (!owns(gen)) return@withContext
            val target = (work ?: pending)?.takeIf { it.generation == gen }
            val label = target?.game?.label ?: archiveName
            val handoff = withdrawWinlatorRequest(gen)
            if (handoff == WinlatorHandoff.Launched) {
                retainAfterWinlatorHandoff(target, label)
                return@withContext
            }
            if (handoff == WinlatorHandoff.Withdrawn) {
                AppLog.i("ManagedUpgrade", "Withdrew the unlaunched Winlator request of gen=$gen")
            }
            if (target != null && (target.newRoot != null || target.state != null)) {
                withContext(Dispatchers.IO) {
                    target.newRoot?.let { root ->
                        val deleted = runCatching { root.deleteRecursively() }.getOrDefault(false)
                        if (!deleted && root.exists()) {
                            AppLog.w("ManagedUpgrade", "Could not delete cancelled folder ${root.absolutePath}")
                        }
                    }
                    clearJournal(target)
                }
            }
            clearInFlightState(clearPending = true)
            phase = JoiPlayExtractFlow.Phase.Preparing
            // The propagating CancellationException reaches this handler again on its way out; the
            // first, best-informed message wins.
            if (cancellationNotice == null && errorMessage == null && result == null) {
                cancellationNotice = ManagedUpgradeCancellation.message(label)
            }
            AppLog.i(
                "ManagedUpgrade",
                "Cancellation accepted for ${label ?: "an upgrade"} gen=$gen: new folder and journal removed",
            )
        }
    }

    /**
     * A cancellation that lost the race with the Winlator hand-off.
     *
     * The intent is already on its way to Winlator, so the new folder may be exactly where the game
     * is about to be run from. Deleting it — or the journal that records where both folders are —
     * would strand the game with no way back, so nothing is touched: the journal is re-saved and the
     * upgrade is surfaced as unresolved. The Activity result (or, after process death, the recovery
     * pass on the next start) reads that journal, verifies where Winlator actually points and either
     * finishes the upgrade or rolls it back.
     */
    private suspend fun retainAfterWinlatorHandoff(target: Pending?, label: String?) {
        if (errorMessage != null || result != null) {
            AppLog.i("ManagedUpgrade", "The Winlator hand-off of ${label ?: "an upgrade"} is already settled")
            return
        }
        withContext(Dispatchers.IO) {
            target?.state?.let { saveJournal(target, it) }
        }
        val subject = label?.takeIf { it.isNotBlank() } ?: "the game"
        fail(
            "The update of $subject was cancelled too late: AGM had already asked Winlator to move " +
                "$subject to the upgraded folder. Nothing was deleted — AGM finishes or undoes the " +
                "update once Winlator reports back, or on the next start if it does not.",
        )
    }

    fun submitPassword(password: String) {
        val p = pending ?: return
        passwordPromptFor = null
        savedPasswordsFailed = false
        lastPasswordWrong = false
        phase = JoiPlayExtractFlow.Phase.Preparing
        progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Preparing ${p.archive.name}…")
        currentJob = scope.launch {
            try {
                when (val outcome = runExtraction(p, password.toCharArray())) {
                    is ArchiveExtractor.Outcome.Ok -> PasswordVault.remember(password)
                    is ArchiveExtractor.Outcome.NeedsPassword -> {
                        lastPasswordWrong = true
                        passwordPromptFor = outcome.format
                        currentJob = null
                    }
                    else -> Unit
                }
            } catch (ce: CancellationException) {
                AppLog.i("ManagedUpgrade", "Cancelled")
                acceptCancellation(p.generation, p)
            }
        }
    }

    private fun onNeedsPasswordInitial(format: ArchiveExtractor.Format) {
        lastPasswordWrong = false
        savedPasswordsFailed = false
        currentJob = null
        if (PasswordVault.isEmpty()) passwordPromptFor = format else autoTryAll(format)
    }

    private fun autoTryAll(format: ArchiveExtractor.Format) {
        val p = pending ?: return
        val candidates = PasswordVault.all()
        phase = JoiPlayExtractFlow.Phase.Preparing
        progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Preparing ${p.archive.name}…")
        currentJob = scope.launch {
            try {
                for ((i, pwd) in candidates.withIndex()) {
                    if (!isActive) return@launch
                    autoTryStatus = "Trying saved password ${i + 1}/${candidates.size}…"
                    when (runExtraction(p, pwd.toCharArray())) {
                        is ArchiveExtractor.Outcome.Ok -> {
                            PasswordVault.remember(pwd)
                            autoTryStatus = null
                            return@launch
                        }
                        is ArchiveExtractor.Outcome.NeedsPassword -> Unit
                        else -> { autoTryStatus = null; return@launch }
                    }
                }
                autoTryStatus = null
                savedPasswordsFailed = true
                passwordPromptFor = format
                currentJob = null
            } catch (ce: CancellationException) {
                AppLog.i("ManagedUpgrade", "Cancelled")
                acceptCancellation(p.generation, p)
            }
        }
    }

    fun cancelPasswordPrompt() {
        cancelInProgress()
    }

    /**
     * Accepts a cancellation from the dialog or the minimized card.
     *
     * Once the boundary has been crossed there is nothing to cancel and pressing Cancel must be
     * impossible, so this refuses outright rather than cancelling a job whose remaining work runs
     * under `NonCancellable`.
     */
    fun cancelInProgress() {
        if (!cancellationAllowed) {
            AppLog.w("ManagedUpgrade", "Ignored a cancel after the commit boundary")
            return
        }
        val job = currentJob
        if (job?.isActive == true) {
            phase = JoiPlayExtractFlow.Phase.Cancelling
            progress = progress ?: ArchiveExtractor.Progress(0L, 0L, 0, 1, "Cancelling…")
            passwordPromptFor = null
            autoTryStatus = null
            job.cancel()
            return
        }
        val work = pending
        clearInFlightState(clearPending = true)
        if (errorMessage == null && result == null) {
            cancellationNotice = ManagedUpgradeCancellation.message(work?.game?.label)
        }
    }

    private fun clearInFlightState(clearPending: Boolean) {
        currentJob = null
        progress = null
        passwordPromptFor = null
        autoTryStatus = null
        savedPasswordsFailed = false
        lastPasswordWrong = false
        archiveName = null
        cancellationAllowed = true
        if (clearPending) pending = null
    }

    fun acknowledgeError() {
        errorMessage = null
    }

    fun acknowledgeCancellation() {
        cancellationNotice = null
    }

    fun acknowledgeResult() {
        result = null
        archiveName = null
        progress = null
        pending = null
    }

    fun onWinlatorLaunchFailed(error: Throwable) {
        // The intent never reached Winlator, so this generation is once again free to be undone.
        synchronized(winlatorHandoffLock) {
            winlatorRequest = null
            winlatorLaunchedSequence = 0L
            winlatorLaunchedGeneration = 0L
        }
        val p = pending
        AppLog.e("ManagedUpgrade", "Could not open Winlator for the upgrade", error)
        if (p == null) {
            fail("Could not open Winlator: ${error.message}")
            return
        }
        currentJob = scope.launch {
            if (p.phase == ManagedUpgradePhase.WinlatorRollbackRequested) {
                failInconsistent(
                    p,
                    "${p.game.label} is now inconsistent: Winlator was moved to the upgraded folder, " +
                        "but AGM could not open Winlator to restore its original path. Both folders were kept.",
                )
            } else {
                abortAfterWinlator(p, "Could not open Winlator: ${error.message}")
            }
        }
    }

    /** Handles the Activity result of both the forward re-point and the compensating rollback. */
    fun onWinlatorResult(resultCode: Int, data: Intent?) {
        val p = pending
        val plan = p?.winlatorPlan
        if (p == null || plan == null) {
            // The in-memory flow was lost (process death or recomposition). Resolve the outcome
            // from the durable journal instead of guessing.
            currentJob = scope.launch { resolveFromJournal() }
            return
        }
        val parsed = WinlatorApi.parseResult(resultCode, data)
        val reported = (parsed as? WinlatorApi.OperationResult.Failure)?.let { "${it.code}: ${it.message}" }
        AppLog.i(
            "ManagedUpgrade",
            "Winlator result phase=${p.phase} success=${parsed is WinlatorApi.OperationResult.Success} $reported",
        )
        phase = JoiPlayExtractFlow.Phase.Preparing
        progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Verifying Winlator…")
        currentJob = scope.launch {
            val live = withContext(Dispatchers.IO) {
                readWinlatorManagedGame(context, plan.winlatorGameId)
            }
            val location = ManagedUpgradeWinlatorVerification.classify(
                live?.gamePath,
                live?.executablePath,
                plan,
            )
            AppLog.i("ManagedUpgrade", "Winlator verification location=$location")
            if (p.phase == ManagedUpgradePhase.WinlatorRollbackRequested) {
                finishRollback(p, location, reported)
                return@launch
            }
            when (location) {
                ManagedUpgradeWinlatorVerification.Location.NewPath -> commit(p, live)
                ManagedUpgradeWinlatorVerification.Location.OldPath -> abortAfterWinlator(
                    p,
                    "Winlator did not move ${p.game.label} to the upgraded folder" +
                        (reported?.let { " ($it)" } ?: "") + ". The installed game was left unchanged.",
                )
                ManagedUpgradeWinlatorVerification.Location.Unknown -> failInconsistent(
                    p,
                    "AGM can't tell where Winlator now expects ${p.game.label}. Both folders were kept: " +
                        "${p.oldRoot.absolutePath} and ${p.newRoot?.absolutePath}.",
                )
            }
        }
    }

    private suspend fun resolveFromJournal() {
        when (val outcome = recoverPendingManagedUpgrade(context)) {
            ManagedUpgradeRecoveryOutcome.None -> {
                progress = null
                currentJob = null
            }
            is ManagedUpgradeRecoveryOutcome.Completed -> {
                progress = null
                currentJob = null
                pending = null
                result = outcome.result
            }
            is ManagedUpgradeRecoveryOutcome.Discarded -> fail(outcome.reason)
            is ManagedUpgradeRecoveryOutcome.Failed -> fail(outcome.message)
        }
    }

    private suspend fun prepareAndExtract(archive: File, app: InstalledApp, gen: Long) = withContext(Dispatchers.IO) {
        val managedGameId = app.managedGameId?.takeIf { it.isNotBlank() }
            ?: return@withContext fail("Only AGM-managed games can be upgraded from an archive.")
        val game = ManagedGameStore(context).find(managedGameId)
            ?: return@withContext fail("This game is no longer in the AGM managed library.")
        val oldRoot = File(game.storagePath)
        if (!oldRoot.isDirectory) {
            return@withContext fail("The installed folder no longer exists: ${game.storagePath}")
        }
        val parent = oldRoot.parentFile
        if (parent == null || !parent.canWrite()) {
            return@withContext fail("AGM can't write next to ${oldRoot.absolutePath}.")
        }
        val blocker = winlatorPreflightRejection(game)
        if (blocker != null) return@withContext fail(blocker)
        val patchBlocker = runCatching {
            ManagedGameStore(context).requireNoUnresolvedPatchTransaction(game.id)
        }.exceptionOrNull()
        if (patchBlocker != null) {
            return@withContext fail(
                patchBlocker.message ?: "${game.label} has an unresolved patch transaction.",
            )
        }

        val p = Pending(
            archive = archive,
            game = game,
            oldRoot = oldRoot,
            destParent = parent,
            generation = gen,
            // Captured once, here, so the whole transaction — including a recovery that finishes it
            // after a restart — honours the preference that was in force when it started.
            deleteSourceArchive = runCatching { JoiPlaySettingsStore.deleteAfterInstall(context) }
                .onFailure { AppLog.w("ManagedUpgrade", "Could not read delete-after-install; keeping the archive", it) }
                .getOrDefault(false),
        )
        pending = p
        phase = JoiPlayExtractFlow.Phase.Extracting
        AppLog.i(
            "ManagedUpgrade",
            "Upgrading ${game.label} id=${game.id} from ${archive.name} old=${oldRoot.absolutePath}",
        )
        notifyStep(ManagedUpgradeStep.Extracting)
        val outcome = runExtraction(p, password = null)
        if (outcome is ArchiveExtractor.Outcome.NeedsPassword) onNeedsPasswordInitial(outcome.format)
    }

    /** Rejects Winlator bindings that cannot be moved *before* anything on disk changes. */
    private fun winlatorPreflightRejection(game: ManagedGame): String? {
        val binding = game.runnerBindings
            .filterIsInstance<ManagedRunnerBinding.Winlator>()
            .singleOrNull() ?: return null
        if (!binding.enabled && game.defaultRunner != ManagedRunnerKind.Winlator) return null
        val executable = binding.executablePath?.trim()?.takeIf { it.isNotBlank() }
        val inside = executable != null && runCatching {
            canonicalManagedGamePath(executable).startsWith("${game.canonicalPath}/")
        }.getOrDefault(false)
        return if (inside) {
            null
        } else {
            "${game.label} runs in Winlator from an executable installed inside its container, not from " +
                "its AGM game folder, so AGM can't upgrade it from an archive."
        }
    }

    private suspend fun runExtraction(
        pendingWork: Pending,
        password: CharArray?,
    ): ArchiveExtractor.Outcome = withContext(Dispatchers.IO) {
        val format = ArchiveExtractor.detectFormatByExt(pendingWork.archive.name)
            ?: pendingWork.archive.inputStream().use { ArchiveExtractor.detectFormat(it) }
        if (format == null) {
            val msg = "Unknown archive format. Supported: ZIP, RAR, 7Z."
            fail(msg)
            return@withContext ArchiveExtractor.Outcome.Failed(msg)
        }
        val outcome = try {
            ArchiveExtractor.extract(
                context = context,
                archive = pendingWork.archive,
                format = format,
                password = password,
                destRoot = ArchiveExtractor.ExtractRoot.FileRoot(pendingWork.destParent),
                suggestedName = pendingWork.archive.nameWithoutExtension,
            ) { p -> progress = p }
        } catch (ce: CancellationException) {
            phase = JoiPlayExtractFlow.Phase.Cancelling
            acceptCancellation(pendingWork.generation, pendingWork)
            throw ce
        }
        when (outcome) {
            is ArchiveExtractor.Outcome.Ok -> finishExtraction(pendingWork, outcome)
            is ArchiveExtractor.Outcome.NeedsPassword -> progress = null
            is ArchiveExtractor.Outcome.Failed -> fail(outcome.message)
            is ArchiveExtractor.Outcome.Cancelled -> acceptCancellation(pendingWork.generation, pendingWork)
        }
        outcome
    }

    private suspend fun finishExtraction(
        pendingWork: Pending,
        outcome: ArchiveExtractor.Outcome.Ok,
    ) {
        val newRoot = (outcome.rootFolder as? ArchiveExtractor.ExtractRoot.FileRoot)?.file
        if (newRoot == null) {
            ArchiveExtractor.deleteExtractedRoot(outcome.rootFolder)
            fail("Upgrades need a normal shared-storage folder, not a document tree.")
            return
        }
        pendingWork.newRoot = newRoot
        AppLog.i("ManagedUpgrade", "Extracted ${pendingWork.archive.name} to ${newRoot.absolutePath}")
        var state = ManagedUpgradePendingState(
            phase = ManagedUpgradePhase.SavesPending,
            managedGameId = pendingWork.game.id,
            label = pendingWork.game.label,
            oldRootPath = pendingWork.oldRoot.absolutePath,
            oldCanonicalPath = pendingWork.game.canonicalPath,
            newRootPath = newRoot.absolutePath,
            newCanonicalPath = canonicalManagedGamePath(newRoot.absolutePath),
            sourceArchivePath = pendingWork.archive.absolutePath,
            deleteSourceArchive = pendingWork.deleteSourceArchive,
            originalGame = pendingWork.game,
            replacementGame = pendingWork.game,
        )
        val journalSaved = runCatching {
            saveJournal(pendingWork, state)
        }.onFailure {
            AppLog.e("ManagedUpgrade", "Could not journal extracted upgrade folder", it)
        }.isSuccess
        if (!journalSaved) {
            newRoot.deleteRecursively()
            fail("AGM could not create a recovery record, so the upgrade was cancelled before changing anything.")
            return
        }
        progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Checking the upgraded files…")

        notifyStep(ManagedUpgradeStep.Inspecting)
        if (checkpoint(pendingWork)) return
        val discovered = runCatching {
            ManagedGameDiscovery.inspect(
                root = newRoot,
                joiPlayAvailable = true,
                winlatorAvailable = true,
                kirikiroidAvailable = true,
            ).candidates.mapNotNull { it.binding }
        }.getOrElse {
            AppLog.w("ManagedUpgrade", "Could not inspect ${newRoot.absolutePath}", it)
            newRoot.deleteRecursively()
            clearJournal(pendingWork)
            fail("AGM could not read the upgraded files: ${it.message}")
            return
        }

        notifyStep(ManagedUpgradeStep.PlanningBindings)
        if (checkpoint(pendingWork)) return
        val plan = ManagedUpgradeBindingMigration.plan(pendingWork.game, newRoot, discovered)
        if (plan is ManagedUpgradeBindingMigration.Outcome.Rejected) {
            newRoot.deleteRecursively()
            clearJournal(pendingWork)
            fail(plan.message)
            return
        }
        val ready = plan as ManagedUpgradeBindingMigration.Outcome.Ready
        if (ready.droppedRunners.isNotEmpty()) {
            AppLog.i("ManagedUpgrade", "Dropping unrepresentable disabled runners: ${ready.droppedRunners}")
        }

        val detectedVersion = JoiPlayVersionDetector.extractVersionFromArchiveName(pendingWork.archive.name)
        val replacement = validateManagedGame(
            ready.replacement.copy(
                versionName = detectedVersion ?: ready.replacement.versionName,
            ),
        )
        state = state.copy(
            newCanonicalPath = replacement.canonicalPath,
            replacementGame = replacement,
            winlator = ready.winlator?.let {
                ManagedUpgradeWinlatorPlan(
                    winlatorGameId = it.winlatorGameId,
                    oldGamePath = it.oldGamePath,
                    oldExecutablePath = it.oldExecutablePath,
                    newGamePath = it.newGamePath,
                    newExecutablePath = it.newExecutablePath,
                )
            },
        )
        notifyStep(ManagedUpgradeStep.Journaling)
        saveJournal(pendingWork, state)

        // The last moment a cancellation can be both observed and undone. Past this line the save
        // migration writes the state the game resumes from and the commit runs NonCancellable, so
        // Cancel is withdrawn from the dialog and the card before the next step starts.
        if (checkpoint(pendingWork)) return
        crossCancellationBoundary(pendingWork)

        notifyStep(ManagedUpgradeStep.MigratingSaves)
        progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Copying save data…")
        val copied = runCatching {
            ManagedUpgradeSaveMigration.migrate(pendingWork.oldRoot, newRoot)
        }.getOrElse {
            AppLog.e("ManagedUpgrade", "Save migration failed for ${pendingWork.game.label}", it)
            withContext(NonCancellable) {
                newRoot.deleteRecursively()
                clearJournal(pendingWork)
            }
            fail("Save data could not be migrated: ${it.message} The upgrade was cancelled and nothing changed.")
            return
        }
        AppLog.i("ManagedUpgrade", "Migrated $copied save artifact(s) into ${newRoot.absolutePath}")

        state = state.copy(saveItemsCopied = copied)
        val winlatorPlan = state.winlator
        if (winlatorPlan == null) {
            state = state.copy(phase = ManagedUpgradePhase.StoreRelocationPending)
            pendingWork.state = state
            saveJournal(pendingWork, state)
            commit(pendingWork, live = null)
            return
        }
        state = state.copy(phase = ManagedUpgradePhase.WinlatorRequested)
        pendingWork.state = state
        notifyStep(ManagedUpgradeStep.RepointingWinlator)
        requestWinlatorRepoint(pendingWork, state, winlatorPlan)
    }

    private suspend fun requestWinlatorRepoint(
        pendingWork: Pending,
        state: ManagedUpgradePendingState,
        plan: ManagedUpgradeWinlatorPlan,
    ) {
        progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Moving the Winlator game…")
        val capabilities = winlatorCapabilityCheck?.invoke()
            ?: WinlatorClient.requiredManagementCapabilities(context).map { }
        if (capabilities.isFailure) {
            val message = capabilities.exceptionOrNull()?.message ?: "Winlator is unavailable."
            abortAfterWinlator(pendingWork, "Winlator can't be reconfigured right now: $message")
            return
        }
        // A cancellation accepted while the capability round-trip was in flight owns the new folder
        // and the journal. Publishing now would hand Winlator a path AGM is about to delete, so the
        // cancellation is completed here instead — nothing was published, so it can undo everything.
        if (!currentCoroutineContext().isActive) {
            AppLog.w("ManagedUpgrade", "Not publishing a Winlator re-point for a cancelled upgrade")
            acceptCancellation(pendingWork.generation, pendingWork)
            return
        }
        saveJournal(pendingWork, state)
        val request = publishWinlatorRequest(
            pendingWork,
            WinlatorApi.configurePortableLocation(
                gameId = plan.winlatorGameId,
                gamePath = plan.newGamePath,
                executablePath = plan.newExecutablePath,
            ),
        )
        if (request == null) {
            acceptCancellation(pendingWork.generation, pendingWork)
            return
        }
        AppLog.i(
            "ManagedUpgrade",
            "Requested Winlator re-point game=${plan.winlatorGameId} -> ${plan.newExecutablePath}",
        )
        winlatorRequestListener?.invoke(request)
    }

    private suspend fun commit(pendingWork: Pending, live: WinlatorApi.ManagedGame?) {
        val state = pendingWork.state ?: run {
            fail("The upgrade lost its recovery record and was stopped before changing anything.")
            return
        }
        progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Finishing the upgrade…")
        cancellationAllowed = false
        notifyStep(ManagedUpgradeStep.Committing)
        val committed = try {
            Result.success(
                withContext(NonCancellable + Dispatchers.IO) {
                    commitManagedUpgrade(context, state, live)
                },
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Result.failure(error)
        }
        committed.onSuccess { upgrade ->
            progress = null
            passwordPromptFor = null
            currentJob = null
            pending = null
            cancellationAllowed = true
            result = upgrade
            AppLog.i(
                "ManagedUpgrade",
                "Upgraded ${upgrade.label}: new=${upgrade.newFolder} old=${upgrade.oldFolder} " +
                    "saves=${upgrade.saveItemsCopied} warnings=${upgrade.warnings.size}",
            )
        }.onFailure { error ->
            AppLog.e("ManagedUpgrade", "Store relocation failed for ${state.label}", error)
            if (state.winlator == null) {
                withContext(NonCancellable + Dispatchers.IO) {
                    pendingWork.newRoot?.deleteRecursively()
                    clearJournal(pendingWork)
                }
                fail("AGM could not update its record for ${state.label}: ${error.message} Nothing was changed.")
            } else {
                requestWinlatorRollback(pendingWork, state, error.message)
            }
        }
    }

    private suspend fun requestWinlatorRollback(
        pendingWork: Pending,
        state: ManagedUpgradePendingState,
        reason: String?,
    ) {
        val plan = requireNotNull(state.winlator)
        val rollbackState = state.copy(phase = ManagedUpgradePhase.WinlatorRollbackRequested)
        pendingWork.state = rollbackState
        pendingWork.rollbackReason = reason
        saveJournal(pendingWork, rollbackState)
        progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Restoring the Winlator game…")
        val request = publishWinlatorRequest(
            pendingWork,
            WinlatorApi.configurePortableLocation(
                gameId = plan.winlatorGameId,
                gamePath = plan.oldGamePath,
                executablePath = plan.oldExecutablePath,
            ),
        )
        if (request == null) {
            failInconsistent(
                pendingWork,
                "${pendingWork.game.label} is now inconsistent: AGM could not ask Winlator to return to " +
                    "${plan.oldExecutablePath}. Both folders were kept; check the game in Winlator.",
            )
            return
        }
        AppLog.w(
            "ManagedUpgrade",
            "Requested compensating Winlator re-point back to ${plan.oldExecutablePath}",
        )
        winlatorRequestListener?.invoke(request)
    }

    private suspend fun finishRollback(
        pendingWork: Pending,
        location: ManagedUpgradeWinlatorVerification.Location,
        reported: String?,
    ) {
        val reason = pendingWork.rollbackReason ?: reported
        if (location == ManagedUpgradeWinlatorVerification.Location.OldPath) {
            withContext(Dispatchers.IO) {
                pendingWork.newRoot?.deleteRecursively()
                clearJournal(pendingWork)
            }
            fail(
                "AGM could not update its record for ${pendingWork.game.label}" +
                    (reason?.let { ": $it" } ?: ".") +
                    " The original installation and its Winlator runner were restored.",
            )
            return
        }
        failInconsistent(
            pendingWork,
            "${pendingWork.game.label} is now inconsistent: AGM still points at " +
                "${pendingWork.oldRoot.absolutePath} but Winlator was not confirmed back on it. " +
                "Both folders were kept; check the game in Winlator before deleting either one.",
        )
    }

    private suspend fun abortAfterWinlator(pendingWork: Pending, message: String) {
        withContext(Dispatchers.IO) {
            pendingWork.newRoot?.deleteRecursively()
            clearJournal(pendingWork)
        }
        fail(message)
    }

    private suspend fun failInconsistent(pendingWork: Pending, message: String) {
        AppLog.e("ManagedUpgrade", message)
        withContext(Dispatchers.IO) {
            pendingWork.state?.let { saveJournal(pendingWork, it) }
        }
        fail(message)
    }

    private fun fail(message: String) {
        progress = null
        passwordPromptFor = null
        autoTryStatus = null
        currentJob = null
        pending = null
        archiveName = null
        cancellationAllowed = true
        errorMessage = message
        AppLog.w("ManagedUpgrade", message)
    }

    private class Pending(
        val archive: File,
        val game: ManagedGame,
        val oldRoot: File,
        val destParent: File,
        val generation: Long,
        val deleteSourceArchive: Boolean,
    ) {
        var newRoot: File? = null
        var state: ManagedUpgradePendingState? = null
        var rollbackReason: String? = null
        val phase: ManagedUpgradePhase?
            get() = state?.phase
        val winlatorPlan: ManagedUpgradeWinlatorPlan?
            get() = state?.winlator
    }
}
